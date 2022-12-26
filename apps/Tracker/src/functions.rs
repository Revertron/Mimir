use ed25519_dalek::{PublicKey, Signature, Verifier};

/// Checks if given signature is valid for given public key and data
pub fn check_signature(public_key: &[u8], signature: &[u8], data: &[u8]) -> bool {
    let public_key = PublicKey::from_bytes(&public_key).unwrap();
    let signature = Signature::from_bytes(&signature).unwrap();
    public_key.verify(&data, &signature).is_ok()
}