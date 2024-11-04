def call(Map config = [:]) {
    def harFilePath = config.harFilePath ?: '/var/jenkins_home/input/t380.vxdev.de.har'
    def zapContainerName = config.zapContainerName ?: "gco-e2e-zap-${env.BUILD_ID}"
    def zapImage = config.zapImage ?: 'zaproxy/zap-stable:2.15.0'
    def zapAlertsSummaryFilename = config.zapAlertsSummaryFilename ?: 'output/owasp-zap-summary-report.json'
    def zapAlertsJsonFilename = config.zapAlertsJsonFilename ?: 'output/owasp-zap-report.json'
    def zapAlertsHtmlFilename = config.zapAlertsHtmlFilename ?: 'output/owasp-zap-report.html'
    def failOnAlerts = config.failOnAlerts?.toBoolean() ?: true

    try {
        // Start the ZAP container
        sh """
            docker stop ${zapContainerName} || true
            docker rm ${zapContainerName} || true
            docker run --name ${zapContainerName} -d ${zapImage} zap.sh -daemon -config api.disablekey=true
        """

        // Wait for ZAP service to start
        def zapReady = false
        for (int i = 0; i < 24; i++) { // Retry for up to 120 seconds
            if (sh(returnStatus: true, script: "docker exec ${zapContainerName} curl -s http://localhost:8080") == 0) {
                zapReady = true
                break
            }
            sleep 5
        }
        if (!zapReady) {
            error "OWASP ZAP service is not ready after 120 seconds. Exiting."
        }

        // Import the HAR file to ZAP
        def filePathUrlEncoded = harFilePath.bytes.encodeBase64().toString()
        sh "docker exec ${zapContainerName} curl -X GET 'http://localhost:8080/JSON/exim/action/importHar/?filePath=${filePathUrlEncoded}'"

        // Wait for ZAP to process the HAR file and generate alerts
        sleep 15

        // Download the ZAP JSON and HTML reports
        sh """
            mkdir -p output
            docker exec ${zapContainerName} curl -s http://localhost:8080/JSON/alert/view/alertsSummary/ > ${zapAlertsSummaryFilename}
            docker exec ${zapContainerName} curl -s http://localhost:8080/JSON/alert/view/alerts/ > ${zapAlertsJsonFilename}
            docker exec ${zapContainerName} curl -s http://localhost:8080/HTML/alert/view/alerts/ > ${zapAlertsHtmlFilename}
        """

        // Display alerts if failOnAlerts is true
        if (failOnAlerts) {
            def alertCount = sh(
                returnStdout: true,
                script: "jq '.alerts | length' ${zapAlertsJsonFilename}"
            ).trim().toInteger()
            if (alertCount > 0) {
                error "ZAP scan found ${alertCount} alerts. Failing the build."
            }
        }
    } finally {
        // Stop and remove the ZAP container
        cleanZapContainer(zapContainerName)
    }
}

// Clean ZAP container
def cleanZapContainer(String containerName) {
    sh """
        docker stop ${containerName} || true
        docker rm ${containerName} || true
    """
}
