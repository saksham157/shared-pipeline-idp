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

            stage('Install Dependencies') {
                steps {
                    sh '''
                        python3 -m venv venv
                        . venv/bin/activate
                        pip install -r requirements.txt
                    '''
                }
            }

            stage('Unit Test') {
                steps {
                    sh '''
                        . venv/bin/activate
                        python3 -m pytest --junitxml=report.xml; echo $? > pytest_exit.txt
                    '''
                    script {
                        def exitCode = readFile('pytest_exit.txt').trim()
                        if (exitCode == '1') {
                            error "Unit tests failed — check report.xml for details"
                        } else if (exitCode == '5') {
                            echo "No tests were collected — continuing, but check this is expected"
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

            stage('Update Helm Values for ArgoCD') {
                steps {
                    script {
                        try {
                            withCredentials([usernamePassword(
                                credentialsId: 'github',
                                usernameVariable: 'GIT_USER',
                                passwordVariable: 'GIT_PASS'
                            )]) {
                                sh """
                                    rm -rf gitops-manifests
                                    git clone https://\$GIT_USER:\$GIT_PASS@github.com/saksham157/gitops-manifests.git
                                    cd gitops-manifests
                                    sed -i 's/tag: .*/tag: "${IMAGE_TAG}"/' python-app-code/values.yaml
                                    git config user.email "jenkins@ci.local"
                                    git config user.name "jenkins-ci"
                                    git add python-app-code/values.yaml
                                    git commit -m "Update image tag to ${IMAGE_TAG} [ci skip]"
                                    git push origin HEAD:main
                                """
                            }
                        } catch (Exception e) {
                            error "Failed to update Helm values for ArgoCD: ${e.message}"
                        }
                    }
                }
            }

        }

        post {
            success {
                echo "Pipeline succeeded — image ${IMAGE_NAME}:${IMAGE_TAG} pushed and Helm values updated."
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