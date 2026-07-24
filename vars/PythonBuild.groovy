def call()

pipeline {

    agent any

    stages{
        stage("python-build"){

            steps{

                sh 'python-version'
            }
        }

        stage("Run Test Script"){

            steps{

                sh 'python3 app.py'
            }
        }
    }

    post {

        success {

            echo 'shared library pipeline run successfully'
        }

        failure {

            echo 'shared library pipeline failed'
        }
    }
}