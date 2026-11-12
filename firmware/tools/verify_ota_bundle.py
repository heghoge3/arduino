#!/usr/bin/env python3
"""Offline verification with the pinned PUBLIC key; no signing secrets required."""
import argparse,hashlib,struct,re
from pathlib import Path
from cryptography.hazmat.primitives import serialization,hashes
from cryptography.hazmat.primitives.asymmetric import ec,utils


def verify(folder):
 manifest=(folder/'manifest.bin').read_bytes();image=(folder/'app.bin').read_bytes();der=(folder/'public.der').read_bytes()
 if len(manifest)!=136 or manifest[:8]!=b'VBO1\x01\x01\x01\x01' or manifest[8:16]!=struct.pack('<I',1)+bytes([2,2,1,0]) or manifest[23]:raise ValueError('Invalid manifest compatibility')
 release,size,minimum=struct.unpack_from('<I',manifest,16)[0],*struct.unpack_from('<II',manifest,24)
 if release!=manifest[20]<<16|manifest[21]<<8|manifest[22] or not 0<minimum<=release:raise ValueError('Invalid version range')
 if size!=len(image) or not 0<size<=0x140000 or image[0]!=0xe9 or image[12:14]!=b'\x09\0':raise ValueError('Invalid image size/chip')
 if manifest[32:64]!=hashlib.sha256(image).digest() or manifest[64:72]!=hashlib.sha256(der).digest()[:8]:raise ValueError('Hash/key ID mismatch')
 key=serialization.load_der_public_key(der)
 if not isinstance(key,ec.EllipticCurvePublicKey) or not isinstance(key.curve,ec.SECP256R1):raise ValueError('Expected P-256')
 r=int.from_bytes(manifest[72:104],'big');s=int.from_bytes(manifest[104:136],'big')
 key.verify(utils.encode_dss_signature(r,s),manifest[:72],ec.ECDSA(hashes.SHA256()))
 metadata=b'VBMETA1\0'+manifest[5:8]+b'\x02'+manifest[8:12]+manifest[16:20]+manifest[20:23]+b'\0'
 if image.count(metadata)!=1:raise ValueError('Product metadata mismatch')
 header=(Path(__file__).resolve().parents[1]/'ota/public-key.h').read_text()
 point=key.public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)
 expected=bytes(int(v) for v in re.search(r'PUBLIC_KEY\[\d+\]=\{([^}]+)',header)[1].split(','))
 if point!=expected:raise ValueError('Firmware public key differs from APK')
 pinned_id=bytes(int(v) for v in re.search(r'ID\[\d+\]=\{([^}]+)',header)[1].split(','))
 if pinned_id!=manifest[64:72]:raise ValueError('Firmware key ID differs from APK')
 return size,hashlib.sha256(image).hexdigest()


def main():
 p=argparse.ArgumentParser();p.add_argument('--dir',type=Path,default=Path(__file__).resolve().parents[2]/'android/app/src/main/assets/firmware');a=p.parse_args()
 size,digest=verify(a.dir);print(f'OTA bundle verified: {size} bytes, SHA256 {digest}')
if __name__=='__main__':main()
