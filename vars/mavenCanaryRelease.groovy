#!/usr/bin/groovy

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def flow = new io.fabric8.Fabric8Commands()
    def utils = new io.fabric8.Utils()

    def skipTests = config.skipTests ?: false

    sh "git checkout -b ${env.JOB_NAME}-${config.version}"
    sh "mvn org.codehaus.mojo:versions-maven-plugin:2.2:set -U -DnewVersion=${config.version}"

    def buildName = ""
    try {
        buildName = utils.getValidOpenShiftBuildName()
    } catch (err) {
        echo "Failed to find buildName due to: ${err}"
    }

    def spaceLabelArg = ""
    if (buildName != null && !buildName.isEmpty()) {
        try {
            def spaceLabel = utils.getSpaceLabelFromBuild(buildName)
            if (!spaceLabel.isEmpty()) {
                spaceLabelArg = "-Dfabric8.enricher.fmp-space-label.space=${spaceLabel}"
            }
        } catch (err) {
            echo "Failed to read space label due to: ${err}"
        }
    }

    sh "mvn clean -B -e -U deploy -Dmaven.test.skip=${skipTests} ${spaceLabelArg} -P openshift"


    junitResults(body);

    if (buildName != null && !buildName.isEmpty()) {
        def buildUrl = "${env.BUILD_URL}"
        if (!buildUrl.isEmpty()) {
            utils.addAnnotationToBuild('fabric8.io/jenkins.testReportUrl', "${buildUrl}testReport")
        }
        def changeUrl = env.CHANGE_URL
        if (changeUrl != null && !changeUrl.isEmpty()) {
            utils.addAnnotationToBuild('fabric8.io/jenkins.changeUrl', changeUrl)
        }

        bayesianScanner(body);
    }



    sonarQubeScanner(body);


    def s2iMode = utils.supportsOpenShiftS2I()
    echo "s2i mode: ${s2iMode}"

    if (!s2iMode) {
        def registry = utils.getDockerRegistry()
        def m = readMavenPom file: 'pom.xml'
        def groupId = m.groupId.split('\\.')
        def user = groupId[groupId.size() - 1].trim()
        def artifactId = m.artifactId

        sh "docker tag ${user}/${artifactId}:${config.version} ${registry}/${user}/${artifactId}:${config.version}"
        if (!flow.isSingleNode()) {
            echo 'Running on a single node, skipping docker push as not needed'
            retry(5) {
                sh "docker tag ${user}/${artifactId}:${config.version} ${registry}/${user}/${artifactId}:${config.version}"
                sh "docker push ${registry}/${user}/${artifactId}:${config.version}"
            }
        }
    }

    contentRepository(body);
  }
