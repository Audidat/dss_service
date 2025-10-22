(ns com.audidat.dss-service.signer
  (:import (eu.europa.esig.dss.alert ExceptionOnStatusAlert LogOnStatusAlert)
           (eu.europa.esig.dss.enumerations DigestAlgorithm SignatureLevel)
           (eu.europa.esig.dss.model FileDocument InMemoryDocument)
           (eu.europa.esig.dss.pades PAdESSignatureParameters)
           (eu.europa.esig.dss.pades.signature PAdESService)
           (eu.europa.esig.dss.service.crl OnlineCRLSource)
           (eu.europa.esig.dss.service.http.commons CommonsDataLoader OCSPDataLoader)
           (eu.europa.esig.dss.service.ocsp OnlineOCSPSource)
           (eu.europa.esig.dss.service.tsp OnlineTSPSource)
           (eu.europa.esig.dss.spi.validation CommonCertificateVerifier)
           (eu.europa.esig.dss.spi.x509 CommonCertificateSource CommonTrustedCertificateSource)
           (eu.europa.esig.dss.token Pkcs12SignatureToken)
           (java.io File FileOutputStream ByteArrayOutputStream)
           (java.security KeyStore$PasswordProtection)))


(defn create-crl-source
  "Creates an online CRL source with extended timeouts for slow servers."
  []
  (let [crl-loader (doto (CommonsDataLoader.)
                     (.setTimeoutConnection 10000)
                     (.setTimeoutSocket 10000))
        crl-source (OnlineCRLSource.)]
    (.setDataLoader crl-source crl-loader)
    crl-source))

(defn create-ocsp-source
  "Creates an online OCSP source."
  []
  (let [ocsp-source (OnlineOCSPSource.)]
    (.setDataLoader ocsp-source (OCSPDataLoader.))
    ocsp-source))

(defn setup-certificate-sources
  "Sets up trusted and adjunct certificate sources.
   Only self-signed (root) certificates are added to the trusted source."
  [cert-chain]
  (let [trusted-source (CommonTrustedCertificateSource.)
        adjunct-source (CommonCertificateSource.)]
    (doseq [cert cert-chain]
      ;; Add all certificates to adjunct source
      (.addCertificate adjunct-source cert)
      ;; Only add self-signed (root) certificates to trusted source
      (when (.isSelfSigned cert)
        (.addCertificate trusted-source cert)))
    {:trusted trusted-source
     :adjunct adjunct-source}))

(defn create-certificate-verifier
  "Creates and configures a certificate verifier with revocation sources."
  [trusted-source adjunct-source crl-source ocsp-source]
  (let [verifier (CommonCertificateVerifier.)]
    (.setCrlSource verifier crl-source)
    (.setOcspSource verifier ocsp-source)
    (.setTrustedCertSources verifier (into-array [trusted-source]))
    (.setAdjunctCertSources verifier (into-array [adjunct-source]))
    (.setCheckRevocationForUntrustedChains verifier true)
    (.setAlertOnMissingRevocationData verifier (LogOnStatusAlert.))
    (.setAlertOnInvalidTimestamp verifier (LogOnStatusAlert.))
    (.setAlertOnNoRevocationAfterBestSignatureTime verifier (LogOnStatusAlert.))
    (.setAlertOnRevokedCertificate verifier (ExceptionOnStatusAlert.))
    (.setAlertOnUncoveredPOE verifier (LogOnStatusAlert.))
    verifier))

(defn create-signature-parameters
  "Creates PAdES signature parameters for LT level with appropriate content size."
  [private-key-entry]
  (doto (PAdESSignatureParameters.)
    (.setSignatureLevel SignatureLevel/PAdES_BASELINE_LT)
    (.setDigestAlgorithm DigestAlgorithm/SHA256)
    (.setSigningCertificate (.getCertificate private-key-entry))
    (.setCertificateChain (.getCertificateChain private-key-entry))
    (.setGenerateTBSWithoutCertificate false)
    (.setContentSize 32768))) ; 32KB for LT signature with revocation data

(defn create-signature-parameters-lta
  "Creates PAdES signature parameters for direct LTA level with appropriate content size."
  [private-key-entry]
  (doto (PAdESSignatureParameters.)
    (.setSignatureLevel SignatureLevel/PAdES_BASELINE_LTA)
    (.setDigestAlgorithm DigestAlgorithm/SHA256)
    (.setSigningCertificate (.getCertificate private-key-entry))
    (.setCertificateChain (.getCertificateChain private-key-entry))
    (.setGenerateTBSWithoutCertificate false)
    (.setContentSize 65536))) ; 64KB for LTA signature with archive timestamp

(defn sign-document
  "Signs a document with PAdES-BASELINE-LT level."
  [service document-to-sign parameters token private-key-entry]
  (let [data-to-sign (.getDataToSign service document-to-sign parameters)
        signature-value (.sign token data-to-sign (.getDigestAlgorithm parameters) private-key-entry)]
    (.signDocument service document-to-sign parameters signature-value)))

(defn extend-to-lta
  "Extends a signed document to PAdES-BASELINE-LTA level."
  [service signed-document]
  (let [lta-params (doto (PAdESSignatureParameters.)
                     (.setSignatureLevel SignatureLevel/PAdES_BASELINE_LTA))]
    (.extendDocument service signed-document lta-params)))

(defn sign-pdf
  "Main function to sign a PDF with PAdES-BASELINE-LTA signature.

   Options map can contain:
   - :input-path - Path to input PDF (default: input.pdf)
   - :output-path - Path to output PDF (default: signed_output.pdf)
   - :p12-path - Path to PKCS12 certificate (default: cotelmur.p12)
   - :p12-password - PKCS12 password (default: 02484012)
   - :tsa-url - Timestamp authority URL (default: http://timestamp.digicert.com)"
  [& {:keys [input-path output-path p12-path p12-password tsa-url]
      :or {input-path "input.pdf"
           output-path "signed_output.pdf"
           p12-path "cotelmur.p12"
           p12-password "02484012"
           tsa-url "http://timestamp.digicert.com"}}]

  ;; Check if input file exists
  (let [input-file (File. input-path)]
    (when-not (.exists input-file)
      (binding [*out* *err*]
        (println (str "Error: Input PDF not found at '" input-path "'")))
      (System/exit 1)))

  ;; Load document and certificate
  (let [document-to-sign (FileDocument. input-path)
        token (Pkcs12SignatureToken. p12-path (KeyStore$PasswordProtection. (.toCharArray p12-password)))
        private-key-entry (first (.getKeys token))
        cert-chain (.getCertificateChain private-key-entry)

        ;; Setup revocation sources
        crl-source (create-crl-source)
        ocsp-source (create-ocsp-source)

        ;; Setup certificate sources
        cert-sources (setup-certificate-sources cert-chain)

        ;; Create certificate verifier
        verifier (create-certificate-verifier
                  (:trusted cert-sources)
                  (:adjunct cert-sources)
                  crl-source
                  ocsp-source)

        ;; Create PAdES service with TSA
        service (doto (PAdESService. verifier)
                  (.setTspSource (OnlineTSPSource. tsa-url)))

        ;; Create signature parameters
        parameters (create-signature-parameters private-key-entry)

        ;; Sign document with LT level
        signed-document (sign-document service document-to-sign parameters token private-key-entry)

        ;; Extend to LTA level
        lta-document (extend-to-lta service signed-document)]

    ;; Save the signed document
    (with-open [fos (FileOutputStream. output-path)]
      (.writeTo lta-document fos))

    ;; Close the token
    (.close token)

    ;; Print success message
    (println (str "Successfully created PAdES-BASELINE-LTA signature: " output-path))))

(defn sign-pdf-bytes
  "Signs a PDF from byte array and returns signed PDF as byte array with direct LTA signature.

   Options map can contain:
   - :pdf-bytes - PDF content as byte array (required)
   - :p12-path - Path to PKCS12 certificate (required)
   - :p12-password - PKCS12 password (required)
   - :tsa-url - Timestamp authority URL (default: http://timestamp.digicert.com)"
  [& {:keys [pdf-bytes p12-path p12-password tsa-url]
      :or {tsa-url "http://timestamp.digicert.com"}}]

  (when-not pdf-bytes
    (throw (IllegalArgumentException. "pdf-bytes is required")))
  (when-not p12-path
    (throw (IllegalArgumentException. "p12-path is required")))
  (when-not p12-password
    (throw (IllegalArgumentException. "p12-password is required")))

  ;; Load document from bytes
  (let [document-to-sign (InMemoryDocument. pdf-bytes)
        token (Pkcs12SignatureToken. p12-path (KeyStore$PasswordProtection. (.toCharArray p12-password)))
        private-key-entry (first (.getKeys token))
        cert-chain (.getCertificateChain private-key-entry)

        ;; Setup revocation sources
        crl-source (create-crl-source)
        ocsp-source (create-ocsp-source)

        ;; Setup certificate sources
        cert-sources (setup-certificate-sources cert-chain)

        ;; Create certificate verifier
        verifier (create-certificate-verifier
                  (:trusted cert-sources)
                  (:adjunct cert-sources)
                  crl-source
                  ocsp-source)

        ;; Create PAdES service with TSA
        service (doto (PAdESService. verifier)
                  (.setTspSource (OnlineTSPSource. tsa-url)))

        ;; Create signature parameters for direct LTA signing
        parameters (create-signature-parameters-lta private-key-entry)

        ;; Sign document directly with LTA level (single timestamp)
        signed-document (sign-document service document-to-sign parameters token private-key-entry)

        ;; Convert to byte array
        output-stream (ByteArrayOutputStream.)]

    (try
      (.writeTo signed-document output-stream)
      (.close token)
      (.toByteArray output-stream)
      (finally
        (.close output-stream)))))

(defn extend-pdf-bytes
  "Extends an already signed PDF to PAdES-BASELINE-LTA level with minimal timestamps.

   Takes a PDF that already has a PAdES signature (BT, LT, etc.) and extends it to LTA.
   Uses direct LTA extension to avoid intermediate LT level (results in 1 timestamp).

   Options map can contain:
   - :pdf-bytes - Signed PDF content as byte array (required)
   - :p12-path - Path to PKCS12 certificate (optional, for validation)
   - :p12-password - PKCS12 password (optional)
   - :tsa-url - Timestamp authority URL (default: http://timestamp.digicert.com)"
  [& {:keys [pdf-bytes p12-path p12-password tsa-url]
      :or {tsa-url "http://timestamp.digicert.com"}}]

  (when-not pdf-bytes
    (throw (IllegalArgumentException. "pdf-bytes is required")))

  ;; Load the signed document
  (let [signed-document (InMemoryDocument. pdf-bytes)

        ;; Create certificate verifier (needed for extension validation)
        ;; If p12 provided, use it for certificate chain, otherwise use minimal setup
        verifier (if (and p12-path p12-password)
                   (let [token (Pkcs12SignatureToken. p12-path (KeyStore$PasswordProtection. (.toCharArray p12-password)))
                         private-key-entry (first (.getKeys token))
                         cert-chain (.getCertificateChain private-key-entry)
                         crl-source (create-crl-source)
                         ocsp-source (create-ocsp-source)
                         cert-sources (setup-certificate-sources cert-chain)]
                     (.close token)
                     (create-certificate-verifier
                      (:trusted cert-sources)
                      (:adjunct cert-sources)
                      crl-source
                      ocsp-source))
                   ;; Minimal verifier without certificate validation
                   (let [crl-source (create-crl-source)
                         ocsp-source (create-ocsp-source)
                         verifier (CommonCertificateVerifier.)]
                     (.setCrlSource verifier crl-source)
                     (.setOcspSource verifier ocsp-source)
                     verifier))

        ;; Create PAdES service with TSA
        service (doto (PAdESService. verifier)
                  (.setTspSource (OnlineTSPSource. tsa-url)))

        ;; Create LTA parameters that extend directly without intermediate LT level
        lta-params (doto (PAdESSignatureParameters.)
                     (.setSignatureLevel SignatureLevel/PAdES_BASELINE_LTA)
                     (.setContentSize 65536)) ; 64KB for LTA with archive timestamp

        ;; Extend directly to LTA level (DSS will add only archive timestamp)
        lta-document (.extendDocument service signed-document lta-params)

        ;; Convert to byte array
        output-stream (ByteArrayOutputStream.)]

    (try
      (.writeTo lta-document output-stream)
      (.toByteArray output-stream)
      (finally
        (.close output-stream)))))

;; (defn -main
;;   "Entry point for the application."
;;   [& args]
;;   (try
;;     (sign-pdf)
;;     (catch Exception e
;;       (binding [*out* *err*]
;;         (println (str "Error signing PDF: " (.getMessage e))))
;;       (.printStackTrace e))))



