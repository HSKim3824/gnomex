pipeline {
  agent any
  stages {
    stage('Initialize') {
      steps {
        echo 'Minimal pipeline'
      }
    }
    stage('Build') {
      steps {
        sh '''PATH=$PATH:/opt/gradle/bin:
gradle -g /home/orionsrvs -c /home/orionsrvs/gradle.properties -debug gnomex_all
'''
      }
    }
    stage('Report') {
      steps {
        emailext(subject: 'Jenkins GNomEx Build', body: 'GNomEx build completed.', to: 'Tim.Maness@hci.utah.edu')
      }
    }
  }
}