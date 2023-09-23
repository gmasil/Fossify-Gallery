pipeline {
  agent {
    docker {
      image "registry.gmasil.de/docker/maven-build-container:latest"
      args "-v /maven:/maven -e JAVA_TOOL_OPTIONS='-Duser.home=/maven' --network servicenet_default -v /usr/lib/android-sdk:/usr/lib/android-sdk"
    }
  }
  environment {
    ANDROID_HOME = "/usr/lib/android-sdk"
  }
  stages {
    stage("Build") {
      steps {
        script {
          sh("find . -name '*.apk' -exec rm {} \\;")
          sh("sed -i 's/Gallery_debug/Gallery/g' ./app/src/debug/res/values/strings.xml")
          sh("gradle clean assembleFoss")
          String apkFile = sh(script: "find ./app/build/outputs/apk/foss/debug/ -name '*.apk'", returnStdout: true).trim()
          String targetApkFile = apkFile.substring(apkFile.lastIndexOf("/") + 1).replace("-debug", "-gmasil")
          sh("mv ${apkFile} ${targetApkFile}")
        }
      }
    }
  }
  post {
    always {
      script {
        archiveArtifacts artifacts: '*.apk', fingerprint: true, allowEmptyArchive: false
        cleanWs()
      }
    }
  }
}
