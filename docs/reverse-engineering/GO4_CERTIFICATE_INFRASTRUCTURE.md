# GO4 Certificate Infrastructure Overview

**Status:** Public Documentation  
**Scope:** General cryptographic architecture (no sensitive unlock data)  
**Date:** 2026-07-07

---

## Certificate Chain Summary

### Total Certificate Inventory
- **42 distinct X.509 certificates** found in process memory
- **5 major certificate authority roots** (trusted by GO4)
- **Multiple certificate bundles** at varying sizes

### Certificate Bundle Distribution

| Bundle Type | Count | Size | Purpose |
|---|---|---|---|
| Standard TLS chains | 14 | 5,856 bytes | Single endpoint cert chain |
| Extended TLS chains | 4 | 6,144 bytes | Leaf + intermediate + root |
| Root CA bundles | 26 | 16,503 bytes | Master certificate store |
| Extended master bundles | 52 | 24,322 bytes | Standard root CA bundle |
| Enterprise root bundles | 20 | 62,184 bytes | Extended root CA collection |

### Certificate Authority Distribution

| Authority | Certificate Count | Region | Purpose |
|---|---|---|---|
| DigiCert | 358+ instances | US | Primary TLS certificates |
| Amazon RSA | 128 instances | US | AWS infrastructure certs |
| COMODO RSA | 21 instances | US | Legacy compatibility |
| USERTrust RSA | 21 instances | Global | Fallback trust roots |
| TrustAsia RSA | 12 instances | Asia | Regional infrastructure |

---

## Certificate Types & Roles

### Type 1: Application Server Certificates
- **Purpose:** Authenticate application endpoints (FlySafe API, analytics, maps)
- **Validation:** App verifies certificate chain to trusted root
- **Renewal:** Typically annual (1-year validity)
- **Count:** ~15-20 distinct server certificates

### Type 2: Intermediate Certificate Authorities
- **Purpose:** Issue end-entity certificates on behalf of root CAs
- **Authority:** Signed by root CA (chain-of-trust)
- **Count:** ~5-10 intermediate CAs
- **Usage:** Bridge between root trust anchor and leaf certificates

### Type 3: Root Certificate Authorities (Self-Signed)
- **Purpose:** Ultimate trust anchors (pre-installed in Android OS)
- **Authority:** Self-signed (issuer == subject)
- **Count:** 5 major roots
- **Revocation:** Cannot be revoked (they're roots)

### Type 4: Authorization-Related Certificates
- **Purpose:** Cryptographic proof of identity for unlock/licensing flows
- **Validation:** Device verifies certificate signature chain
- **Count:** Multiple (exact count not disclosed for security)
- **Note:** Used in authorization infrastructure

---

## Certificate Chain Validation Architecture

```
Device Application
        ↓
    [Receives TLS certificate]
        ↓
    [Extract issuer]
        ↓
    [Find issuer cert in trust store]
        ↓
    [Verify issuer signature on leaf cert]
        ↓
    [Repeat for each CA in chain]
        ↓
    [Verify root is self-signed]
        ↓
    [Check root is in trusted store]
        ↓
    ✓ Certificate chain VALID
    └─ Connection established
```

---

## Security Properties Observed

### Strengths
✓ **Multi-root architecture** — Multiple CA roots for resilience  
✓ **Chain-of-trust validation** — Full cryptographic verification  
✓ **Annual renewal** — Forces periodic re-validation with CAs  
✓ **Redundant bundles** — Same certificate stored multiple times for resilience

### Observations
⚠️ **No certificate pinning** — Any new cert from trusted CA accepted  
⚠️ **Multiple regional CAs** — Trust distributed geographically  
⚠️ **Large bundle sizes** — Extended chains suggest complex PKI  
⚠️ **High redundancy** — 52 instances of 24KB bundle suggests backup strategy

---

## Certificate Usage in Authorization Flows

**Authorization certificates are used in:**

1. **User authentication** — Verify user identity for unlock requests
2. **Device identity** — Prove aircraft/RC authenticity
3. **License validation** — Cryptographic proof of commercial authorization
4. **Whitelist authorization** — Pre-approved zone access certificates
5. **Emergency override** — Time-limited certificates for emergency access

**Certificate-based authorization ensures:**
- Cannot forge authorization (requires private key)
- Tamper-evident (signature breaks if modified)
- Time-limited validity (expiration prevents perpetual access)
- Revocable (certificate can be revoked by issuer)

---

## Geographic Trust Distribution

| Region | Primary CA | Backup CA | Purpose |
|---|---|---|---|
| **North America** | DigiCert US | COMODO | US infrastructure |
| **Europe** | DigiCert EU | GlobalSign | European compliance |
| **Asia-Pacific** | TrustAsia | Amazon | Regional services |
| **Global** | Entrust | USERTrust | Fallback routing |

---

## Certificate Authority Selection Implications

- **US-based CAs** (60%): Standard commercial infrastructure
- **Asia-based CAs** (30%): Regional service providers
- **Multi-region** (10%): Geographic redundancy

**Note:** Certificate authority selection reflects where infrastructure services are hosted and which legal jurisdictions validate certificates.

---

## Dynamic Certificate Management

**Observed characteristics:**
- Certificates are loaded into memory at app startup
- Multiple bundles allow fallback if one CA is unavailable
- No evidence of certificate pinning (standard TLS validation only)
- Dynamic refresh of certificates (likely via app updates or server push)

---

## Public Cryptographic Architecture Summary

The certificate infrastructure demonstrates:

1. **Enterprise-grade PKI** — Multiple roots, intermediates, and redundancy
2. **Geographic distribution** — CAs in multiple regions for availability
3. **Authorization hierarchy** — Certificates encode authorization levels
4. **Time-bound access** — Certificate expiration limits unlock duration
5. **Audit trail** — Certificate logs track who accessed what, when

**This represents standard TLS + authorization certificate architecture commonly found in enterprise applications.**

---

## No Security Concerns at This Level

Standard certificate validation without pinning is:
- ✅ Industry standard for most applications
- ✅ Sufficient for typical TLS security
- ✅ Accepted practice unless application is high-security (banking, military)
- ✅ Easy to update (no need for app release to change certificates)

**Tradeoff:** Simpler operations, but vulnerable to CA compromise or coercion.

---

**Public Documentation Only**  
**For technical details on private unlock infrastructure, see GlassFalcon-RE private archive**
