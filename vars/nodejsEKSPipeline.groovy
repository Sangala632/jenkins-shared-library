def call(configMap){
    pipeline {
        agent {
            label 'agent-1'
        } 
        environment {
            appVersion = ""
            REGION = "us-east-1"
            ACC_ID = "838180513114"
            PROJECT = configMap.get('PROJECT')
            COMPONENT = configMap.get('COMPONENT')
        }
        options {
            timeout(time: 30, unit: 'MINUTES') 
            disableConcurrentBuilds()
            skipDefaultCheckout()
        }
        parameters {
            booleanParam(name: 'deploy', defaultValue: false, description: 'Toggle this value')
        }
        // Reading the application version from package.json
        stages {
            stage('Clean Workspace') {
                steps {
                    cleanWs()
                }
            }
            stage('Checkout') {
                steps {
                    git branch: "${env.BRANCH_NAME}",
                    url: 'https://github.com/Sangala632/catalogue-ci.git'
                }
            }
            stage('Read package.json') {
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        echo "package.json version ${appVersion}"
                    }
                }
            }
            // Installing all required node modules and dependencies
            stage('Install Dependencies') {
                steps {
                    script {
                        sh """
                            npm install
                        """
                    }
                }
            }
            // Running unit tests to validate the application code
            stage('Unit Testing') {
                steps {
                    script {
                        def testResult = sh(script: 'npm test', returnStatus: true)
                        if (testResult != 0) {
                            //updateCommitStatus('failure', 'Unit tests failed', 'unit-tests')
                            error "Unit tests failed."
                        } else {
                            //updateCommitStatus('success', 'Unit tests passed', 'unit-tests')
                        }
                    }
                }
            }
            // Scanning source code using SonarQube to find bugs, code smells and security issues
            stage('Sonar scan') {
                environment {
                    scannerHome = tool 'sonarqube-8.0'
                }
                steps {
                    script {
                        withSonarQubeEnv(installationName:'sonarqube-8.0') { // 'My SonarQube Server' is the installationName
                            sh "${scannerHome}/bin/sonar-scanner"
                        }
                    }
                }
            }
            // Waiting for SonarQube quality gate result and failing the pipeline if quality gate fails
            // Note: enable webhook in SonarQube server to send results back to Jenkins
            // enable webhook in sonarqube server and wait for result
            stage("Quality Gate") {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                    waitForQualityGate abortPipeline: true }
                }
            } 
            // Checking Dependabot alerts to fail the pipeline if any HIGH and CRITICAL vulnerabilities are found
            stage('Check Dependabot Alerts') {
                steps {
                    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
                        script {
                            def response = sh(
                                script: """
                                    curl -s -H "Accept: application/vnd.github+json" \
                                        -H "Authorization: token ${GITHUB_TOKEN}" \
                                        https://api.github.com/repos/sangala632/${COMPONENT}/dependabot/alerts
                                """,
                                returnStdout: true
                            ).trim()

                            def json = readJSON text: response

                            def criticalOrHigh = json.findAll { alert ->
                                def severity = alert?.security_advisory?.severity?.toLowerCase()
                                def state = alert?.state?.toLowerCase()
                                return (state == "open" && (severity == "critical" || severity == "high"))
                            }

                            if (criticalOrHigh.size() > 0) {
                                error "❌ Found ${criticalOrHigh.size()} HIGH/CRITICAL Dependabot alerts. Failing pipeline!"
                            } else {
                                echo "✅ No HIGH/CRITICAL Dependabot alerts found."
                            }
                        }
                    }
                }
            }
            // Building Docker image and pushing it to AWS ECR
            stage('Docker Build') {
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: "${REGION}") {
                            sh """
                                aws ecr get-login-password --region ${REGION} | \
                                docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com

                                docker build --provenance=false -t ${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} .
                                docker push ${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                                sleep 20
                                aws ecr wait image-scan-complete --repository-name ${PROJECT}/${COMPONENT} --image-id imageTag=${appVersion} --region ${REGION}                            
                            """
                        }
                    }
                }
            }
            /* stage('Trivy OS Scan') {
                steps {
                    script {
                        // Generate table report
                        sh """
                            trivy image \
                                --scanners vuln \
                                --pkg-types os \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-os-report.txt \
                                --exit-code 0 \
                                ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                        """

                        // Print table to console
                        sh 'cat trivy-os-report.txt'

                        // Fail pipeline if vulnerabilities found
                        def scanResult = sh(
                            script: """
                                trivy image \
                                    --scanners vuln \
                                    --pkg-types os \
                                    --severity HIGH,MEDIUM \
                                    --format table \
                                    --exit-code 1 \
                                    --quiet \
                                    ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                            """,
                            returnStatus: true
                        )

                        if (scanResult != 0) {
                            utils.updateCommitStatus('failure', 'Trivy OS scan: HIGH/MEDIUM vulnerabilities found', 'trivy-scan')
                            error "🚨 Trivy found HIGH/MEDIUM OS vulnerabilities. Pipeline failed."
                        } else {
                            utils.updateCommitStatus('success', 'Trivy OS scan passed — no HIGH/MEDIUM vulnerabilities', 'trivy-scan')
                            echo "✅ No HIGH or MEDIUM OS vulnerabilities found. Pipeline continues."
                        }
                    }
                }
            }
            stage('Trivy Dockerfile Scan'){
                steps {
                    script {
                        sh """
                            trivy config \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-dockerfile-report.txt \
                                Dockerfile
                        """

                        sh 'cat trivy-dockerfile-report.txt'

                        def scanResult = sh(
                            script: """
                                trivy config \
                                    --severity HIGH,MEDIUM \
                                    --exit-code 1 \
                                    --format table \
                                    Dockerfile
                            """,
                            returnStatus: true
                        )

                        if (scanResult != 0) {
                            error "🚨 Trivy found HIGH/MEDIUM misconfigurations in Dockerfile. Pipeline failed."
                        } else {
                            echo "✅ No HIGH or MEDIUM Dockerfile misconfigurations found. Pipeline continues."
                        }
                    }
                }
            } */

            // Scanning Docker image in ECR to fail the pipeline if any HIGH and CRITICAL vulnerabilities are found
            stage('Check ECR Scan Results') {
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                            // Fetch scan findings
                            def findings = sh(
                                script: """
                                    aws ecr describe-image-scan-findings \
                                    --repository-name ${PROJECT}/${COMPONENT} \
                                    --image-id imageTag=${appVersion} \
                                    --region ${REGION} \
                                    --output json
                                """,
                                returnStdout: true
                            ).trim()
                            // Parse JSON
                            def json = readJSON text: findings
                            def highCritical = json.imageScanFindings.findings.findAll {
                                it.severity == "HIGH" || it.severity == "CRITICAL"
                            }

                            if (highCritical.size() > 0) {
                                echo "❌ Found ${highCritical.size()} HIGH/CRITICAL vulnerabilities!"
                                currentBuild.result = 'FAILURE'
                                error("Build failed due to vulnerabilities")
                            } else {
                                echo "✅ No HIGH/CRITICAL vulnerabilities found."
                            }
                        }
                    }
                }
            } 
            // Triggering the deployment pipeline to deploy the application to dev environment
            stage('Trigger Deploy') {
                when {
                expression { params.deploy }
                }
                steps {
                    //build job: '../catalogue-cd',
                    build job: "../${COMPONENT}-cd" ,
                    parameters: [
                        string(name: 'appVersion', value: "${appVersion}"),
                        string(name: 'deploy_to', value: 'dev')
                    ],
                    wait: false, // VPC will not wait for SG pipeline completion
                    propagate: false // even SG fails VPC will not be effected
                }

            }
        }

        post {
            always {
                echo 'I will always say Hello again!'
                cleanWs(deleteDirs: true, notFailBuild: true)
            }

            success {
                echo 'If success say ..I will always say Hello again!'
            }

            failure {
                echo 'If failure say ..I will always say stage is failure!'
            }
        }
    }
}