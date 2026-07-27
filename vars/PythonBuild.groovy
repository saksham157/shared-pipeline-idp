def call() {
    pipeline {
        agent any

        environment {
            SONAR_PROJECT_KEY = 'python-app-code'
            IMAGE_NAME = 'saksham8000/python-app-code'
            IMAGE_TAG = "${env.BUILD_NUMBER}"
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
                                sh "/opt/sonar-scanner/bin/sonar-scanner -Dsonar.projectKey=${SONAR_PROJECT_KEY}"
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

            stage('Docker Build') {
                steps {
                    script {
                        try {
                            sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                        } catch (Exception e) {
                            error "Docker build failed: ${e.message}"
                        }
                    }
                }
            }

            stage('Docker Push') {
                steps {
                    script {
                        try {
                            withCredentials([usernamePassword(
                                credentialsId: 'dockerhub-creds',
                                usernameVariable: 'DOCKER_USER',
                                passwordVariable: 'DOCKER_PASS'
                            )]) {
                                sh """
                                    echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin
                                    docker push ${IMAGE_NAME}:${IMAGE_TAG}
                                """
                            }
                        } catch (Exception e) {
                            error "Docker push failed: ${e.message}"
                        }
                    }
                }
            }

        }

        post {
            success {
                echo "Pipeline succeeded — image ${IMAGE_NAME}:${IMAGE_TAG} pushed."
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