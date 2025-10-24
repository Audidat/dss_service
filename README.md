# DSS Service - PAdES Digital Signature Service

A Clojure-based microservice for creating PAdES-BASELINE-LTA (PDF Advanced Electronic Signatures - Long Term Archive) digital signatures using the EU DSS Framework.

## Features

- **Sign PDFs** with PAdES-BASELINE-LTA signatures
- **Extend existing signatures** to LTA level for long-term validity
- **Certificate-based signing** using P12/PKCS12 certificates or PEM format
- **Timestamp support** with configurable TSA (Timestamp Authority)
- **EU DSS Framework** compliance for legal electronic signatures

## Prerequisites

- Java 11 or higher
- Clojure CLI tools
- A valid P12/PKCS12 certificate for signing

## Configuration

### Environment Variables

The service requires environment variables for configuration. Copy `.env.example` to `.env` and configure:

```bash
cp .env.example .env
```

Edit `.env` with your configuration:

```bash
# REQUIRED: Password for your P12 certificate
P12_PASSWORD=your_actual_password

# OPTIONAL: Path to P12 certificate (defaults to cotelmur.p12)
P12_PATH=path/to/your/certificate.p12

# OPTIONAL: Timestamp Authority URL (defaults to DigiCert)
TSA_URL=http://timestamp.digicert.com

# OPTIONAL: Server port (defaults to 4000)
PORT=4000
```

**⚠️ IMPORTANT SECURITY NOTES:**
- Never commit your `.env` file to version control
- Never share your P12 password
- Keep your certificate files secure and backed up
- The `.env` file is already in `.gitignore`

## Running the Service

### Development

```bash
# Load environment variables and start the service
source .env
clj -M:run
```

The service will start on `http://localhost:4000` (or the port configured in `.env`).

### Production

For production deployment, set environment variables in your deployment platform (Docker, Kubernetes, etc.) rather than using `.env` files.

## API Endpoints

### POST /api/sign
Sign a PDF with P12 certificate (configured via environment variables).

**Request:**
- Body: Raw PDF bytes
- Content-Type: application/pdf

**Response:**
- Content-Type: application/pdf
- Body: Signed PDF with PAdES-BASELINE-LTA signature

### POST /api/extend
Extend an existing signed PDF to LTA level.

**Request:**
- Body: Raw signed PDF bytes
- Content-Type: application/pdf

**Response:**
- Content-Type: application/pdf
- Body: PDF extended to PAdES-BASELINE-LTA

### POST /api/sign-with-cert
Sign a PDF with provided PEM certificate and private key.

**Request:**
- Content-Type: multipart/form-data
- Fields:
  - `pdf`: PDF file
  - `certificate_pem`: PEM-encoded certificate (text)
  - `private_key_pem`: PEM-encoded private key (text)
  - `tsa_url`: Timestamp Authority URL (text)

**Response:**
- Content-Type: application/pdf
- Body: Signed PDF with PAdES-BASELINE-LTA signature

### POST /api/extend-with-cert
Extend a signed PDF to LTA using provided PEM certificate.

**Request:**
- Content-Type: multipart/form-data
- Fields:
  - `pdf`: Signed PDF file
  - `certificate_pem`: PEM-encoded certificate (text)
  - `private_key_pem`: PEM-encoded private key (text)
  - `tsa_url`: Timestamp Authority URL (text)

**Response:**
- Content-Type: application/pdf
- Body: PDF extended to PAdES-BASELINE-LTA

## Testing

```bash
# Sign a test PDF
curl -X POST http://localhost:4000/api/sign \
  -H "Content-Type: application/pdf" \
  --data-binary @test.pdf \
  -o signed.pdf
```

## Project Structure

```
dss_service/
├── src/main/clojure/com/audidat/dss_service/
│   ├── core.clj           # Main entry point
│   ├── handler.clj        # HTTP handlers
│   ├── signer.clj         # PDF signing logic
│   └── routes.clj         # Route definitions
├── deps.edn               # Clojure dependencies
├── .env.example           # Environment configuration template
├── .gitignore             # Git ignore rules
└── README.md              # This file
```

## Technology Stack

- **Clojure** 1.11.1
- **EU DSS Framework** 6.3 - European Digital Signature Service
- **Apache PDFBox** 3.0.2 - PDF manipulation
- **Ring** - HTTP server and middleware
- **Compojure** - HTTP routing

## License

[Your License Here]

## Support

For issues or questions, please open an issue on the GitHub repository.
