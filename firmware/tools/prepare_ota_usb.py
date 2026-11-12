#!/usr/bin/env python3
"""One-time V2 bootstrap. Requires physical confirmation, ON and a PRIVATE full flash backup."""
import argparse,os,re,subprocess,sys,time,struct,hashlib
from pathlib import Path


def check_layout(contents):
 entries={}
 for at in range(0x8000,0x8c00,32):
  magic,kind,subtype,offset,size,label,flags=struct.unpack_from('<HBBII16sI',contents,at)
  if magic!=0x50aa:break
  name=label.split(b'\0')[0].decode('ascii')
  if name in entries:raise SystemExit('Duplicate partition label; nothing flashed')
  entries[name]=(kind,subtype,offset,size,flags)
 expected={'nvs':(1,2,0x9000,0x5000,0),'otadata':(1,0,0xe000,0x2000,0),
           'spiffs':(1,0x82,0x310000,0xe0000,0),'coredump':(1,3,0x3f0000,0x10000,0)}
 if any(entries.get(k)!=v for k,v in expected.items()):raise SystemExit('Unexpected old data layout: backup saved, nothing flashed; manual migration required')
 huge={**expected,'app0':(0,0x10,0x10000,0x300000,0)}
 dual={**expected,'app0':(0,0x10,0x10000,0x180000,0),'app1':(0,0x11,0x190000,0x180000,0)}
 if entries not in (huge,dual):raise SystemExit('Unexpected app or extra data partitions; nothing flashed')


def main():
 p=argparse.ArgumentParser();p.add_argument('--port',required=True);p.add_argument('--backup',required=True,type=Path)
 p.add_argument('--confirm-v2',action='store_true');p.add_argument('--apply',action='store_true');a=p.parse_args()
 if not a.apply or not a.confirm_v2:raise SystemExit('Read README. Both --apply and --confirm-v2 are required; keep slider ON on verified power.')
 root=Path(__file__).resolve().parents[1];backup=a.backup.resolve()
 protected=root.parent if (root.parent/".git").exists() else root
 if backup.is_relative_to(protected.resolve()) or backup.exists():raise SystemExit('Use a NEW private backup path outside the repository; never overwrite it')
 artifacts=root/'bootstrap'
 if not artifacts.is_dir():artifacts=root/'.pio/build/s3_ota_bootstrap'
 framework=Path(os.environ.get('PLATFORMIO_CORE_DIR',Path.home()/'.platformio'))/'packages/framework-arduinoespressif32'
 bootapp=artifacts/'boot_app0.bin'
 if not bootapp.exists():bootapp=framework/'tools/partitions/boot_app0.bin'
 files=[(0,artifacts/'bootloader.bin'),(0x8000,artifacts/'partitions.bin'),(0xe000,bootapp),(0x10000,artifacts/'firmware.bin')]
 if not all(f.is_file() for _,f in files):raise SystemExit('Bootstrap files missing; use the complete bundle')
 sums=root/'SHA256SUMS'
 if not sums.is_file():raise SystemExit('SHA256SUMS missing; use the complete bootstrap bundle')
 entries={line.split('  ',1)[1]:line.split('  ',1)[0] for line in sums.read_text().splitlines() if '  ' in line}
 for _,file in files:
  relative=file.relative_to(root).as_posix()
  if entries.get(relative)!=hashlib.sha256(file.read_bytes()).hexdigest():raise SystemExit('Bootstrap checksum mismatch; nothing flashed')
 base=[sys.executable,'-m','esptool','--chip','esp32s3','--port',a.port]
 detected=subprocess.check_output(base+['flash_id'],stderr=subprocess.STDOUT,text=True)
 if not re.search(r'Detected flash size:\s*4MB',detected):raise SystemExit('Expected measured 4MB flash; refusing migration')
 security=subprocess.check_output(base+['get_security_info'],stderr=subprocess.STDOUT,text=True)
 flags=re.search(r'Flags:\s*(0x[0-9a-fA-F]+)',security);crypt=re.search(r'Flash_Crypt_Cnt:\s*(0x[0-9a-fA-F]+)',security)
 if not flags or not crypt or int(flags[1],16) or int(crypt[1],16):raise SystemExit('Security/eFuse configuration is not the supported blank baseline; refusing unsigned USB migration')
 backup.parent.mkdir(parents=True,exist_ok=True);backup.touch(mode=0o600,exist_ok=False)
 subprocess.run(base+['--after','no_reset','read_flash','0','0x400000',str(backup)],check=True);backup.chmod(0o600)
 if backup.stat().st_size!=0x400000:raise SystemExit('Backup incomplete: nothing flashed')
 check=backup.with_suffix(backup.suffix+'.verify');check.touch(mode=0o600,exist_ok=False)
 try:
  subprocess.run(base+['--before','no_reset','--after','no_reset','read_flash','0','0x400000',str(check)],check=True)
  if backup.read_bytes()!=check.read_bytes():raise SystemExit('Backup readback differs: nothing flashed')
 finally:check.unlink(missing_ok=True)
 # No erase_flash, no writes to NVS/SPIFFS/coredump. The external SD is never formatted.
 contents=backup.read_bytes();check_layout(contents)
 checksum=backup.with_suffix(backup.suffix+'.sha256');checksum.write_text(hashlib.sha256(contents).hexdigest()+'\n');checksum.chmod(0o600)
 command=base+['--before','no_reset','write_flash','--flash_size','4MB']
 for address,file in files:command += [hex(address),str(file)]
 subprocess.run(command,check=True)
 import serial
 time.sleep(6)
 with serial.Serial(a.port,115200,timeout=1) as stream:
  stream.dtr=True;time.sleep(4);stream.reset_input_buffer();stream.write(b'ota provision\n');stream.flush()
  result=bytearray();deadline=time.monotonic()+12
  while time.monotonic()<deadline:
   result.extend(stream.read(256))
   if b'V2 OTA commissioned' in result:print('V2 bootstrap and explicit OTA commissioning confirmed');return
 raise SystemExit('Bootstrap flashed, commissioning NOT confirmed. Keep ON; inspect USB and run ota provision after self-test.')
if __name__=='__main__':main()
