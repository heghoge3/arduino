#!/usr/bin/env python3
"""Sign an existing real V2 app image. Never generate a key or use the APK key."""
import argparse,hashlib,struct,re
from pathlib import Path
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec,utils


def main():
 p=argparse.ArgumentParser();p.add_argument('--key',required=True,type=Path);p.add_argument('--image',required=True,type=Path);p.add_argument('--out',required=True,type=Path);a=p.parse_args()
 key=serialization.load_pem_private_key(a.key.read_bytes(),None)
 if not isinstance(key,ec.EllipticCurvePrivateKey) or not isinstance(key.curve,ec.SECP256R1):raise SystemExit('Expected saved P-256 OTA key')
 data=a.image.read_bytes()
 if not 1<=len(data)<=0x140000 or data[0]!=0xe9 or data[12:14]!=b'\x09\x00':raise SystemExit('Invalid ESP32-S3 app image or size')
 records=[];at=0
 while True:
  at=data.find(b'VBMETA1\0',at)
  if at<0:break
  m=data[at:at+24];at+=8
  if len(m)==24 and m[8:12]==bytes([1,1,1,2]) and m[12:16]==struct.pack('<I',1):records.append(m)
 if len(records)!=1 or records[0][23]!=0:raise SystemExit('Expected exactly one real V2 slider metadata block; refusing fake/bench')
 meta=records[0];release=struct.unpack_from('<I',meta,16)[0]
 if release!=meta[20]<<16|meta[21]<<8|meta[22]:raise SystemExit('Build version/release mismatch')
 pub=key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
 pinned=(Path(__file__).resolve().parents[2]/'android/app/src/main/assets/firmware/public.der').read_bytes()
 if pub!=pinned:raise SystemExit('Wrong OTA signing key')
 header=(Path(__file__).resolve().parents[1]/'ota/public-key.h').read_text()
 values=lambda label:bytes(int(v) for v in re.search(label+r'\[\d+\]=\{([^}]+)',header)[1].split(','))
 point=key.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)
 if values('PUBLIC_KEY')!=point or values('ID')!=hashlib.sha256(pub).digest()[:8]:raise SystemExit('Firmware and APK OTA keys do not match')
 body=bytearray(72);body[:4]=b'VBO1';body[4:8]=bytes([1,1,1,1]);struct.pack_into('<I',body,8,1)
 body[12:16]=bytes([2,2,1,0]);struct.pack_into('<I',body,16,release);body[20:23]=meta[20:23]
 struct.pack_into('<II',body,24,len(data),0x0000011d) # minimum receiver 0.1.29
 body[32:64]=hashlib.sha256(data).digest();body[64:72]=hashlib.sha256(pub).digest()[:8]
 signed=key.sign(bytes(body),ec.ECDSA(hashes.SHA256()));r,s=utils.decode_dss_signature(signed)
 manifest=bytes(body)+r.to_bytes(32,'big')+s.to_bytes(32,'big')
 key.public_key().verify(signed,body,ec.ECDSA(hashes.SHA256()))
 a.out.mkdir(parents=True,exist_ok=True);(a.out/'manifest.bin').write_bytes(manifest);(a.out/'app.bin').write_bytes(data)
 print(f'Signed V2 {meta[20]}.{meta[21]}.{meta[22]}, {len(data)} bytes; SHA256 {hashlib.sha256(data).hexdigest()}')
if __name__=='__main__':main()
