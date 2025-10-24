# DSS Service API Documentation

## Overview

The DSS Service provides a REST API for signing PDF documents with PAdES-BASELINE-LTA (Long Term Archive) digital signatures using the EU DSS Framework.

## Configuration

The service is configured via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `P12_PATH` | Path to PKCS12 certificate file | `cotelmur.p12` |
| `P12_PASSWORD` | Password for the PKCS12 certificate | `02484012` |
| `TSA_URL` | Timestamp Authority URL | `http://timestamp.digicert.com` |

### Setting Environment Variables

```bash
export P12_PATH=/path/to/certificate.p12
export P12_PASSWORD=your_password
export TSA_URL=http://timestamp.digicert.com
```

## Running the Service

```bash
# Start the service
clj -M:run

# The service will start on port 4000
```

## API Endpoints

### Health Check

**Endpoint:** `GET /health`

Check if the service is running.

```bash
curl http://localhost:4000/health
```

**Response:**
```
ok
```

---

### Sign PDF

**Endpoint:** `POST /api/sign`

Sign a PDF document with PAdES-BASELINE-LTA signature.

**Request:**
- **Method:** POST
- **Content-Type:** `application/pdf` (or `application/octet-stream`)
- **Body:** Raw PDF binary data

**Response:**
- **Content-Type:** `application/pdf`
- **Content-Disposition:** `attachment; filename="signed.pdf"`
- **Body:** Signed PDF binary data

#### Example with curl

```bash
# Sign a PDF file
curl -X POST \
  -H "Content-Type: application/pdf" \
  --data-binary "@input.pdf" \
  -o signed.pdf \
  http://localhost:4000/api/sign
```

#### Example with Ruby (for DocuSeal integration)

```ruby
require 'net/http'
require 'uri'

def sign_pdf(pdf_data)
  uri = URI('http://localhost:4000/api/sign')

  http = Net::HTTP.new(uri.host, uri.port)
  request = Net::HTTP::Post.new(uri.path)
  request['Content-Type'] = 'application/pdf'
  request.body = pdf_data

  response = http.request(request)

  if response.code == '200'
    response.body # Signed PDF bytes
  else
    raise "Signing failed: #{response.body}"
  end
end

# Usage
pdf_bytes = File.binread('input.pdf')
signed_pdf = sign_pdf(pdf_bytes)
File.binwrite('signed.pdf', signed_pdf)
```

#### Example with Python

```python
import requests

def sign_pdf(pdf_path, output_path):
    with open(pdf_path, 'rb') as f:
        pdf_data = f.read()

    response = requests.post(
        'http://localhost:4000/api/sign',
        headers={'Content-Type': 'application/pdf'},
        data=pdf_data
    )

    if response.status_code == 200:
        with open(output_path, 'wb') as f:
            f.write(response.content)
        print(f'Signed PDF saved to {output_path}')
    else:
        print(f'Error: {response.text}')

# Usage
sign_pdf('input.pdf', 'signed.pdf')
```

#### Example with JavaScript/Node.js

```javascript
const fs = require('fs');
const axios = require('axios');

async function signPdf(inputPath, outputPath) {
  const pdfBuffer = fs.readFileSync(inputPath);

  try {
    const response = await axios.post('http://localhost:4000/api/sign', pdfBuffer, {
      headers: { 'Content-Type': 'application/pdf' },
      responseType: 'arraybuffer'
    });

    fs.writeFileSync(outputPath, response.data);
    console.log(`Signed PDF saved to ${outputPath}`);
  } catch (error) {
    console.error('Signing failed:', error.response?.data || error.message);
  }
}

// Usage
signPdf('input.pdf', 'signed.pdf');
```

## Error Responses

### 400 Bad Request

Invalid request (e.g., empty body).

```json
{
  "error": "Request body is empty"
}
```

### 500 Internal Server Error

Server error during signing process.

```json
{
  "error": "Internal server error: <error message>"
}
```

Common errors:
- Certificate file not found
- Invalid certificate password
- Malformed PDF
- Network issues connecting to TSA

## Signature Details

The service creates **PAdES-BASELINE-LTA** signatures with the following characteristics:

### `/api/sign` - Direct LTA Signing

1. **Signature Level:** PAdES-BASELINE-LTA (Long Term Archive) - **created in one step**
2. **Digest Algorithm:** SHA-256
3. **Timestamp:** Single archive timestamp obtained from configured TSA
4. **Revocation Data:** Includes CRL and OCSP responses embedded in signature
5. **Content Size:** 64KB reserved for signature data

**Result:** PDF with **1 timestamp** (archive timestamp only)

### `/api/extend` - Extend Existing Signatures to LTA

1. **Input:** PDF with existing PAdES signature (BT, LT, etc.)
2. **Process:** Extends directly to LTA level with archive timestamp
3. **Timestamp:** Adds single archive timestamp
4. **Revocation Data:** Adds revocation data and archival timestamp
5. **Content Size:** 64KB reserved for extension data

**Result:** PDF with **1 additional timestamp** (archive timestamp)

**Note:** This endpoint extends the existing signature to LTA. The total number of timestamps in the final PDF may vary depending on what was already in the input signature.

---

### Sign PDF with Certificate (Database-Driven)

**Endpoint:** `POST /api/sign-with-cert`

Sign a PDF document using certificate data from multipart form (for multi-tenant/database-driven scenarios).

**Request:**
- **Method:** POST
- **Content-Type:** `multipart/form-data`
- **Body Parameters:**
  - `pdf` - PDF file (binary)
  - `certificate_pem` - PEM-encoded X.509 certificate (text)
  - `private_key_pem` - PEM-encoded PKCS#8 private key (text, unencrypted)
  - `tsa_url` - Timestamp Authority URL (text)

**Response:**
- **Content-Type:** `application/pdf`
- **Content-Disposition:** `attachment; filename="signed.pdf"`
- **Body:** Signed PDF binary data

#### Example with curl

```bash
curl -X POST http://localhost:4000/api/sign-with-cert \
  -F "pdf=@input.pdf" \
  -F "certificate_pem=-----BEGIN CERTIFICATE-----
MIIDXTCCAkWgAwIBAgIJAKZ...
-----END CERTIFICATE-----" \
  -F "private_key_pem=-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0B...
-----END PRIVATE KEY-----" \
  -F "tsa_url=http://timestamp.digicert.com" \
  -o signed.pdf
```

#### Example with Ruby

```ruby
require 'net/http'
require 'uri'

def sign_pdf_with_cert(pdf_data, cert_pem, key_pem, tsa_url)
  uri = URI('http://localhost:4000/api/sign-with-cert')

  boundary = "----RubyMultipartBoundary#{SecureRandom.hex(16)}"

  body_parts = []
  body_parts << "--#{boundary}\r\n"
  body_parts << "Content-Disposition: form-data; name=\"pdf\"; filename=\"document.pdf\"\r\n"
  body_parts << "Content-Type: application/pdf\r\n\r\n"
  body_parts << pdf_data
  body_parts << "\r\n"

  body_parts << "--#{boundary}\r\n"
  body_parts << "Content-Disposition: form-data; name=\"certificate_pem\"\r\n\r\n"
  body_parts << cert_pem
  body_parts << "\r\n"

  body_parts << "--#{boundary}\r\n"
  body_parts << "Content-Disposition: form-data; name=\"private_key_pem\"\r\n\r\n"
  body_parts << key_pem
  body_parts << "\r\n"

  body_parts << "--#{boundary}\r\n"
  body_parts << "Content-Disposition: form-data; name=\"tsa_url\"\r\n\r\n"
  body_parts << tsa_url
  body_parts << "\r\n"

  body_parts << "--#{boundary}--\r\n"

  http = Net::HTTP.new(uri.host, uri.port)
  request = Net::HTTP::Post.new(uri.path)
  request['Content-Type'] = "multipart/form-data; boundary=#{boundary}"
  request.body = body_parts.join

  response = http.request(request)
  response.code == '200' ? response.body : nil
end
```

---

### Extend PDF with Certificate (Database-Driven)

**Endpoint:** `POST /api/extend-with-cert`

Extend a signed PDF to LTA using certificate data from multipart form (for multi-tenant/database-driven scenarios).

**Request:**
- **Method:** POST
- **Content-Type:** `multipart/form-data`
- **Body Parameters:**
  - `pdf` - Signed PDF file (binary)
  - `certificate_pem` - PEM-encoded X.509 certificate (text)
  - `private_key_pem` - PEM-encoded PKCS#8 private key (text, unencrypted)
  - `tsa_url` - Timestamp Authority URL (text)

**Response:**
- **Content-Type:** `application/pdf`
- **Content-Disposition:** `attachment; filename="extended_lta.pdf"`
- **Body:** Extended PDF binary data

#### Example with curl

```bash
curl -X POST http://localhost:4000/api/extend-with-cert \
  -F "pdf=@signed.pdf" \
  -F "certificate_pem=@certificate.pem" \
  -F "private_key_pem=@private_key.pem" \
  -F "tsa_url=http://timestamp.digicert.com" \
  -o extended.pdf
```

**Key Differences from File-Based Endpoints:**
- Certificate and private key passed as PEM text (not file paths)
- Password decryption handled by caller (DocuSeal)
- Enables multi-tenant scenarios (each request can use different certificate)
- Private key must be in PKCS#8 format (unencrypted)
- All processing done in-memory (no temporary files)

## Integration with DocuSeal

To integrate with DocuSeal, you can call this service from a background job:

```ruby
# app/jobs/sign_pdf_with_dss_job.rb
class SignPdfWithDssJob < ApplicationJob
  queue_as :default

  def perform(document_id)
    document = CompletedDocument.find(document_id)
    pdf_data = document.file.download

    # Call DSS service
    uri = URI('http://localhost:4000/api/sign')
    response = Net::HTTP.post(uri, pdf_data, 'Content-Type' => 'application/pdf')

    if response.code == '200'
      # Save signed PDF
      document.file.attach(
        io: StringIO.new(response.body),
        filename: "#{document.filename}_signed.pdf",
        content_type: 'application/pdf'
      )
    else
      raise "DSS signing failed: #{response.body}"
    end
  end
end
```

## Testing

### Prerequisites

1. Valid PKCS12 certificate file
2. Sample PDF file

### Run Tests

```bash
# Health check
curl http://localhost:4000/health

# Sign a test PDF
curl -X POST \
  -H "Content-Type: application/pdf" \
  --data-binary "@test.pdf" \
  -o signed_test.pdf \
  http://localhost:4000/api/sign

# Verify the signature was applied
# You can use Adobe Reader or other PDF tools to verify the signature
```

## Production Deployment

### Using Docker

Create a `Dockerfile`:

```dockerfile
FROM clojure:temurin-21-tools-deps

WORKDIR /app
COPY . /app

# Copy your P12 certificate
COPY certificate.p12 /app/certificate.p12

ENV P12_PATH=/app/certificate.p12
ENV P12_PASSWORD=your_password
ENV TSA_URL=http://timestamp.digicert.com

EXPOSE 4000

CMD ["clojure", "-M:run"]
```

Build and run:

```bash
docker build -t dss-service .
docker run -p 4000:4000 dss-service
```

### Using systemd

Create `/etc/systemd/system/dss-service.service`:

```ini
[Unit]
Description=DSS PDF Signing Service
After=network.target

[Service]
Type=simple
User=dss
WorkingDirectory=/opt/dss-service
Environment="P12_PATH=/opt/dss-service/certificate.p12"
Environment="P12_PASSWORD=your_password"
Environment="TSA_URL=http://timestamp.digicert.com"
ExecStart=/usr/bin/clj -M:run
Restart=always

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl enable dss-service
sudo systemctl start dss-service
```

## Troubleshooting

### Certificate Not Found

```
Error: Internal server error: certificate.p12
```

**Solution:** Ensure the P12 certificate file exists at the path specified in `P12_PATH`.

### Invalid Certificate Password

```
Error: Internal server error: ...
```

**Solution:** Verify the `P12_PASSWORD` environment variable is correct.

### TSA Connection Issues

```
Error: Internal server error: Connection refused
```

**Solution:** Check network connectivity to the TSA URL. You may need to configure proxy settings or use a different TSA.

### Malformed PDF

```
Error: Internal server error: ...
```

**Solution:** Ensure the input is a valid PDF file.

## Performance Considerations

- Each signature request creates CRL/OCSP connections for certificate validation
- CRL/OCSP timeouts are set to 10 seconds
- Timestamp requests may take 1-3 seconds
- Average signing time: 2-5 seconds per document
- Server uses thread pool with 8 threads and queue size of 2048

## Security Notes

1. **Certificate Security:** Keep P12 certificates and passwords secure. Use environment variables or secret management systems.
2. **HTTPS:** In production, use HTTPS with reverse proxy (nginx, Apache).
3. **Authentication:** Add authentication layer if exposing publicly.
4. **Rate Limiting:** Consider adding rate limiting for public endpoints.

## Support

For issues related to the EU DSS Framework, consult:
- [EU DSS Documentation](https://ec.europa.eu/digital-building-blocks/wikis/display/DIGITAL/Digital+Signature+Service)
- [DSS GitHub Repository](https://github.com/esig/dss)
