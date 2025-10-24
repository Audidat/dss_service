(ns com.audidat.dss-service.handler
  (:require [com.audidat.dss-service.signer :as signer]
            [ring.middleware.multipart-params :refer [multipart-params-request]]
            [clojure.java.io :as io])
  (:import (java.io ByteArrayOutputStream)))

(defn get-config
  "Get configuration from environment variables.
   Throws exception if required environment variables are not set."
  []
  (let [p12-path (System/getenv "P12_PATH")
        p12-password (System/getenv "P12_PASSWORD")
        tsa-url (or (System/getenv "TSA_URL") "http://timestamp.digicert.com")]
    (when-not p12-password
      (throw (IllegalStateException. "P12_PASSWORD environment variable must be set")))
    {:p12-path (or p12-path "cotelmur.p12")
     :p12-password p12-password
     :tsa-url tsa-url}))

(defn read-body-bytes
  "Reads the entire request body into a byte array."
  [request]
  (let [body (:body request)]
    (if body
      (with-open [baos (ByteArrayOutputStream.)]
        (let [buffer (byte-array 8192)]
          (loop []
            (let [n (.read body buffer)]
              (when (pos? n)
                (.write baos buffer 0 n)
                (recur))))
          (.toByteArray baos)))
      (throw (IllegalArgumentException. "Request body is empty")))))

(defn sign-pdf-handler
  "Handler for POST /api/sign endpoint.
   Accepts raw PDF bytes in request body and returns signed PDF."
  [request]
  (try
    (let [config (get-config)
          pdf-bytes (read-body-bytes request)
          signed-pdf (signer/sign-pdf-bytes
                      :pdf-bytes pdf-bytes
                      :p12-path (:p12-path config)
                      :p12-password (:p12-password config)
                      :tsa-url (:tsa-url config))]
      {:status 200
       :headers {"content-type" "application/pdf"
                 "content-disposition" "attachment; filename=\"signed.pdf\""}
       :body (java.io.ByteArrayInputStream. signed-pdf)})
    (catch IllegalArgumentException e
      {:status 400
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"" (.getMessage e) "\"}")})
    (catch Exception e
      (println "Error signing PDF:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"Internal server error: " (.getMessage e) "\"}")})))

(defn extend-pdf-handler
  "Handler for POST /api/extend endpoint.
   Accepts a signed PDF (PAdES-BT or any level) in request body and extends it to LTA."
  [request]
  (try
    (let [config (get-config)
          pdf-bytes (read-body-bytes request)
          extended-pdf (signer/extend-pdf-bytes
                        :pdf-bytes pdf-bytes
                        :p12-path (:p12-path config)
                        :p12-password (:p12-password config)
                        :tsa-url (:tsa-url config))]
      {:status 200
       :headers {"content-type" "application/pdf"
                 "content-disposition" "attachment; filename=\"extended_lta.pdf\""}
       :body (java.io.ByteArrayInputStream. extended-pdf)})
    (catch IllegalArgumentException e
      {:status 400
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"" (.getMessage e) "\"}")})
    (catch Exception e
      (println "Error extending PDF to LTA:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"Internal server error: " (.getMessage e) "\"}")})))

(defn read-file-bytes
  "Reads bytes from an uploaded file (multipart param)."
  [file-param]
  (if (map? file-param)
    ;; Ring multipart file is a map with :tempfile, :bytes, or :content
    (if-let [bytes (:bytes file-param)]
      bytes
      (if-let [temp-file (:tempfile file-param)]
        (with-open [baos (ByteArrayOutputStream.)]
          (io/copy temp-file baos)
          (.toByteArray baos))
        (throw (IllegalArgumentException. "File parameter has no :bytes or :tempfile"))))
    ;; If it's already bytes, return as-is
    file-param))

(defn get-text-param
  "Extracts text parameter from multipart params."
  [params key]
  (or (get params key)
      (throw (IllegalArgumentException. (str "Missing required parameter: " (name key))))))

(defn sign-pdf-with-cert-handler
  "Handler for POST /api/sign-with-cert endpoint.
   Accepts multipart form data with:
   - pdf: PDF file bytes
   - certificate_pem: PEM-encoded certificate (text)
   - private_key_pem: PEM-encoded private key (text)
   - tsa_url: Timestamp Authority URL (text)
   Returns signed PDF with PAdES-BASELINE-LTA."
  [request]
  (try
    ;; Parse multipart form data
    (let [request (multipart-params-request request)
          params (:multipart-params request)
          pdf-bytes (read-file-bytes (get params "pdf"))
          certificate-pem (get-text-param params "certificate_pem")
          private-key-pem (get-text-param params "private_key_pem")
          tsa-url (get-text-param params "tsa_url")
          signed-pdf (signer/sign-pdf-with-pem
                      :pdf-bytes pdf-bytes
                      :certificate-pem certificate-pem
                      :private-key-pem private-key-pem
                      :tsa-url tsa-url)]
      {:status 200
       :headers {"content-type" "application/pdf"
                 "content-disposition" "attachment; filename=\"signed.pdf\""}
       :body (java.io.ByteArrayInputStream. signed-pdf)})
    (catch IllegalArgumentException e
      {:status 400
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"" (.getMessage e) "\"}")})
    (catch Exception e
      (println "Error signing PDF with certificate:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"Internal server error: " (.getMessage e) "\"}")})))

(defn extend-pdf-with-cert-handler
  "Handler for POST /api/extend-with-cert endpoint.
   Accepts multipart form data with:
   - pdf: Signed PDF file bytes (PAdES-BT or any level)
   - certificate_pem: PEM-encoded certificate (text)
   - private_key_pem: PEM-encoded private key (text)
   - tsa_url: Timestamp Authority URL (text)
   Returns PDF extended to PAdES-BASELINE-LTA."
  [request]
  (try
    ;; Parse multipart form data
    (let [request (multipart-params-request request)
          params (:multipart-params request)
          pdf-bytes (read-file-bytes (get params "pdf"))
          certificate-pem (get-text-param params "certificate_pem")
          private-key-pem (get-text-param params "private_key_pem")
          tsa-url (get-text-param params "tsa_url")
          extended-pdf (signer/extend-pdf-with-pem
                        :pdf-bytes pdf-bytes
                        :certificate-pem certificate-pem
                        :private-key-pem private-key-pem
                        :tsa-url tsa-url)]
      {:status 200
       :headers {"content-type" "application/pdf"
                 "content-disposition" "attachment; filename=\"extended_lta.pdf\""}
       :body (java.io.ByteArrayInputStream. extended-pdf)})
    (catch IllegalArgumentException e
      {:status 400
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"" (.getMessage e) "\"}")})
    (catch Exception e
      (println "Error extending PDF with certificate:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"content-type" "application/json"}
       :body (str "{\"error\":\"Internal server error: " (.getMessage e) "\"}")})))
