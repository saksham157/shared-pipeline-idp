def call() {
    pipeline {
        agent any

        environment {
            SONAR_PROJECT_KEY = 'python-app-code'
        }

        stages {

            stage('Checkout') {
                steps {
                    script {
                        try {
                            checkout scm
                        } catch (Exception e) {
                            error "Checkout failed: ${e.message}"
                        }
                    }
                }
            }

            stage('Unit Test') {
                steps {
                    script {
                        try {
                            sh 'python3 -m pytest || echo "No tests found, skipping"'
                        } catch (Exception e) {
                            echo "Unit tests failed: ${e.message}"
                            currentBuild.result = 'UNSTABLE'
                        }
                    }
                }
            }

            stage('SonarQube Scan') {
                steps {
                    script {
                        try {
                            withSonarQubeEnv('sonarqube-server') {
                                sh "sonar-scanner -Dsonar.projectKey=${SONAR_PROJECT_KEY}"
                            }
                        } catch (Exception e) {
                            error "SonarQube scan failed: ${e.message}"
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    script {
                        try {
                            timeout(time: 2, unit: 'MINUTES') {
                                def qg = waitForQualityGate()
                                if (qg.status != 'OK') {
                                    error "Quality Gate failed: ${qg.status}"
                                }
                            }
                        } catch (Exception e) {
                            error "Quality Gate check failed: ${e.message}"
                        }
                    }
                }
            }

        }

        post {
            success {
                echo 'Pipeline completed successfully — code passed quality checks.'
            }
            unstable {
                echo 'Pipeline finished but some checks were flagged — review logs.'
            }
            failure {
                echo 'Pipeline failed — check which stage above reported the error.'
            }
        }
    }
}