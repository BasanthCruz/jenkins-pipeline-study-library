def call(Map config = [:]) {
    def harDirectoryPath = config.harDirectoryPath ?: '/var/jenkins_home/input'
    def zapContainerName = config.zapContainerName ?: "vx-e2e-zap-${env.BUILD_ID}"
    def zapImage = config.zapImage ?: 'zaproxy/zap-stable:2.15.0'
    def zapAlertsSummaryFilename = config.zapAlertsSummaryFilename ?: 'output/owasp-zap-summary-report.json'
    def zapAlertsJsonFilename = config.zapAlertsJsonFilename ?: 'output/owasp-zap-report.json'
    def zapAlertsHtmlFilename = config.zapAlertsHtmlFilename ?: 'output/owasp-zap-report.html'

    try {
        echo "Starting OWASP ZAP container with name: ${zapContainerName} and mounting HAR directory."

        // Start the ZAP container with volume mount for HAR directory
        sh "docker run --name ${zapContainerName} -v ${harDirectoryPath}:/zap/wrk/input -d ${zapImage} zap.sh -daemon -config api.disablekey=true"

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
        
        echo "OWASP ZAP service is up. Importing HAR files from directory: ${harDirectoryPath}"
        // Find and import each HAR file in the directory
        def harDirectory = new File(harDirectoryPath)
        if (harDirectory.exists() && harDirectory.isDirectory()) {
            harDirectory.listFiles().each { harFile ->
                if (harFile.name.endsWith(".har")) {
                    echo "Sending HAR file '${harFile.name}' to OWASP ZAP ..."

                    // Pass unencoded file path as it's directly accessible in the container
                    def harFilePathInContainer = "/zap/wrk/input/${harFile.name}"
                    sh "docker exec ${zapContainerName} curl -X GET 'http://localhost:8080/JSON/exim/action/importHar/?filePath=${harFilePathInContainer}'"
                }
            }
        } else {
            error "HAR directory not found or is not a directory: ${harDirectoryPath}"
        }
        
        // Wait for ZAP to process the HAR file and generate alerts
        sleep 15

        // Download the ZAP JSON and HTML reports
        sh """
            mkdir -p output
            docker exec ${zapContainerName} curl -s http://localhost:8080/JSON/alert/view/alertsSummary/ > ${zapAlertsSummaryFilename}
            docker exec ${zapContainerName} curl -s http://localhost:8080/JSON/alert/view/alerts/ > ${zapAlertsJsonFilename}
            docker exec ${zapContainerName} curl -s http://localhost:8080/HTML/alert/view/alerts/ > ${zapAlertsHtmlFilename}
        """

        // Archive artifacts only if the above steps succeed
        archiveZapArtifacts([zapAlertsSummaryFilename, zapAlertsJsonFilename, zapAlertsHtmlFilename])


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

// Archive ZAP artifacts
def archiveZapArtifacts(List<String> filenames) {
    filenames.each { filename ->
        if (fileExists(filename)) {
            archiveArtifacts artifacts: filename, allowEmptyArchive: true
            echo "Archived artifact: ${filename}"
        } else {
            echo "Warning: Artifact not found - ${filename}"
        }
    }
}
