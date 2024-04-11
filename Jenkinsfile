pipeline {
  agent {
    docker {
      image "registry.gmasil.de/docker/maven-build-container:latest"
      args "-v /maven:/maven -e JAVA_TOOL_OPTIONS='-Duser.home=/maven' -v /usr/lib/android-sdk:/usr/lib/android-sdk -v /root/gmasil-gallery-keystore.jks:/keystore/keystore.jks -v /root/gmasil-gallery-keystore.properties:/keystore/keystore.properties"
    }
  }
  environment {
    ANDROID_HOME = "/usr/lib/android-sdk"
  }
  stages {
    stage("Build") {
      steps {
        script {
          sh("cp /keystore/* .")
          sh("find . -name '*.apk' -exec rm {} \\;")
          sh("gradle clean assembleFoss")
          String apkFile = sh(script: "find ./app/build/outputs/apk/foss/release/ -name '*.apk'", returnStdout: true).trim()
          sh("mv ${apkFile} gmasil-gallery.apk")
        }
      }
    }
  }
  post {
    always {
      script {
        archiveArtifacts artifacts: 'gmasil-gallery.apk', fingerprint: true, allowEmptyArchive: false
        cleanWs()
      }
    }
  }
}
