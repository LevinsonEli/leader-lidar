
def call() {

  pipeline {
    agent any

    tools {
        maven 'MavenTool'
        jdk 'OracleJDK8'
    }

    environment {
      GIT_URL = 'git@gitlab.com:jenkins1444027/testing.git'

      SETTINGS_XML_ID = 'artifactory_settings_xml'
      CREDENTIALS_ID = 'artifactory_admin_credentials'
      SSH_CREDENTIALS_ID = 'ssh_to_gitlab'
      PERSONAL_ACCESS_TOKEN_ID = 'Personal_GitLab_Access_Token'
      REGISTRY_URL = 'registry.gitlab.com'
      REGISTRY_USERNAME = 'eliyahulevinson@gmail.com'
    }

    stages {
      stage('Checkout') {
        when {
          anyOf {
            branch 'master'
          }
        }
        steps {
          script {
            println "---------------------   Checkout Stage   ---------------------"
            deleteDir()
            checkout scm
          }
        }
      }

      stage('Build') {
        when {
          anyOf {
            branch 'master'
          }
        }
        steps {
          script {
            println "---------------------   Build Stage   ---------------------"
            withCredentials([usernamePassword(credentialsId: "${CREDENTIALS_ID}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              configFileProvider([configFile(fileId: "${SETTINGS_XML_ID}", variable: 'SETTINGS_XML_FILE')]) {
                sh """
                  mvn clean package -U --batch-mode -s ${SETTINGS_XML_FILE} -Dmaven.test.skip=true -Dusername=${USERNAME} -Dpassword=${PASSWORD}
                  mvn install -s ${SETTINGS_XML_FILE} -Dmaven.test.skip=true -Dusername=${USERNAME} -Dpassword=${PASSWORD}
                """
              }
            }
          }
        }
      }

      stage('Calculate Tag') {
        when {
          anyOf {
            branch 'master'
          }
        }
        
        steps {
          sshagent([env.SSH_CREDENTIALS_ID]) {
            script {
              println "---------------------   Preparing for Publish: Calculate Tag Stage   ---------------------"
              MAJOR_VERSION = 1
              MINOR_VERSION = 0
            }
          }
        }
      }

      stage('E2E Tests') {
        when {
          anyOf {
            branch 'master'
          }
        }
        steps {
          script {
            println "---------------------   E2E Tests Stage   ---------------------"
            def commitMessage = sh(script: "git log -1 --pretty=%B ${env.GIT_COMMIT}", returnStdout: true).trim()
            if ( (GIT_BRANCH.startsWith('feature/') || GIT_BRANCH.startsWith('origin/feature/')) && !commitMessage.contains('#e2e') ) {
              return
            }

            withCredentials([usernamePassword(credentialsId: "${env.CREDENTIALS_ID}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              configFileProvider([configFile(fileId: "${env.SETTINGS_XML_ID}", variable: 'SETTINGS_XML_FILE')]) {

                simulator_cur = sh(script: "ls ./target | grep simulator", returnStdout: true).trim()

                sh "chmod +x get_latest.sh"
                def telemetry_lst = sh(script: "./get_latest.sh telemetry ${MAJOR_VERSION} ${MINOR_VERSION} ${USERNAME} ${PASSWORD} libs-release-local", returnStdout: true).trim()
                TELEMETRY_LST = "${MAJOR_VERSION}.${MINOR_VERSION}.${telemetry_lst}"

                def analytics_lst = sh(script: "./get_latest.sh analytics ${MAJOR_VERSION} ${MINOR_VERSION} ${USERNAME} ${PASSWORD} libs-release-local", returnStdout: true).trim()
                ANALYTICS_LST = "${MAJOR_VERSION}.${MINOR_VERSION}.${analytics_lst}"

                sh "curl -u ${USERNAME}:${PASSWORD} -O http://artifactory:8081/artifactory/libs-release-local/com/lidar/analytics/${ANALYTICS_LST}/analytics-${ANALYTICS_LST}.jar"
                sh "curl -u ${USERNAME}:${PASSWORD} -O http://artifactory:8081/artifactory/libs-release-local/com/lidar/telemetry/${TELEMETRY_LST}/telemetry-${TELEMETRY_LST}.jar"

                // Devide tests file into 5 parts
                sh "chmod +x split_tests.sh"
                sh './split_tests.sh 5 tests.txt'
              }
            }
          }
        }
      }
      
      stage('Running E2E Tests in Parallel') {
        when {
          anyOf {
            branch 'master'
          }
        }
        parallel {
          stage('Part 1') {
            steps {
              script {
                runE2ETestsPartly(1, simulator_cur, TELEMETRY_LST, ANALYTICS_LST)
              }
            }
          }
          stage('Part 2') {
            steps {
              script {
                runE2ETestsPartly(2, simulator_cur, TELEMETRY_LST, ANALYTICS_LST)
              }
            }
          }
          stage('Part 3') {
            steps {
              script {
                runE2ETestsPartly(3, simulator_cur, TELEMETRY_LST, ANALYTICS_LST)
              }
            }
          }
          stage('Part 4') {
            steps {
              script {
                runE2ETestsPartly(4, simulator_cur, TELEMETRY_LST, ANALYTICS_LST)
              }
            }
          }
          stage('Part 5') {
            steps {
              script {
                runE2ETestsPartly(5, simulator_cur, TELEMETRY_LST, ANALYTICS_LST)
              }
            }
          }
        }
      }


      stage('Set Version') {
        when {
          anyOf {
            branch 'master'
          }
        }
        steps {
          script {
              println "---------------------   Preparing for Publish: Set Version Stage   ---------------------"
            sh "mvn versions:set -DnewVersion=latest"
            sh "mvn versions:commit"
          }
        }
      }

      stage('Publish') {
        when {
          anyOf {
            branch 'master'
          }
        }
        steps {
          script {
            println "---------------------   Publish Stage   ---------------------"
            withCredentials([usernamePassword(credentialsId: "${env.CREDENTIALS_ID}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              configFileProvider([configFile(fileId: "${env.SETTINGS_XML_ID}", variable: 'SETTINGS_XML_FILE')]) {
                sh """
                  mvn deploy -s ${SETTINGS_XML_FILE} -DskipTests -Dusername=${USERNAME} -Dpassword=${PASSWORD}
                """
              }
            }
          }
        }
      }
    }

    post {
      success {
        emailext body: "Your last build successed",
        subject: "SUCCESS",
        to: "${env.REGISTRY_USERNAME}"
      }

      failure {
        emailext body: "Your last build failed",
        subject: "FAILURE",
        to: "${env.REGISTRY_USERNAME}"
      }
    }
  }
}

def runE2ETestsPartly(part, simulator_cur, telemetry_lst, analytics_lst) {
  if (fileExists("splited_tests/part0${part}.txt")) {
    println "---------------------   E2E Tests Part $part Stage   ---------------------"
    def part1Workspace = pwd() + "/part0$part"
    dir(part1Workspace) {
      sh "cat ../splited_tests/part0${part}.txt > tests.txt"
      sh "java -cp ../target/${simulator_cur}:../telemetry-${telemetry_lst}.jar:../analytics-${analytics_lst}.jar com.lidar.simulation.Simulator"
    }
  }
}