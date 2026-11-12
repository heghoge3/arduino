"""USB migration must reject layouts that would lose data; public bundle tamper checks."""
import struct,tempfile,unittest
from pathlib import Path
from prepare_ota_usb import check_layout
from verify_ota_bundle import verify

BASE=[('nvs',1,2,0x9000,0x5000),('otadata',1,0,0xe000,0x2000),
      ('app0',0,0x10,0x10000,0x300000),('spiffs',1,0x82,0x310000,0xe0000),
      ('coredump',1,3,0x3f0000,0x10000)]
def flash(entries):
 data=bytearray(b'\xff'*0x9000)
 for i,(name,kind,subtype,at,size) in enumerate(entries):
  struct.pack_into('<HBBII16sI',data,0x8000+32*i,0x50aa,kind,subtype,at,size,name.encode(),0)
 return data
class BootstrapTests(unittest.TestCase):
 def test_old_and_new_layout_keep_data(self):
  check_layout(flash(BASE))
  entries=BASE.copy();entries[2]=('app0',0,0x10,0x10000,0x180000)
  check_layout(flash(entries+[('app1',0,0x11,0x190000,0x180000)]))
 def test_missing_moved_extra_and_duplicate_data_refused(self):
  bad=[BASE[:-1],BASE+[('unknown',1,0x40,0x350000,0x1000)],BASE+[BASE[0]],
       [('nvs',1,2,0x9000,0x6000)]+BASE[1:]]
  for entries in bad:
   with self.subTest(entries=entries),self.assertRaises(SystemExit):check_layout(flash(entries))
 def test_wrong_second_slot_and_incomplete_ab_refused(self):
  entries=BASE.copy();entries[2]=('app0',0,0x10,0x10000,0x180000)
  for extra in [[],[('app1',0,0x11,0x180000,0x180000)]]:
   with self.assertRaises(SystemExit):check_layout(flash(entries+extra))
 def test_public_bundle_and_tamper_refused(self):
  source=Path(__file__).resolve().parents[2]/'android/app/src/main/assets/firmware'
  verify(source)
  for name,offset in [('app.bin',100),('manifest.bin',72),('manifest.bin',6),('public.der',-1)]:
   with self.subTest(name=name,offset=offset),tempfile.TemporaryDirectory() as folder:
    target=Path(folder)
    for file in source.iterdir():
     if file.is_file(): (target/file.name).write_bytes(file.read_bytes())
    data=bytearray((target/name).read_bytes());data[offset]^=1;(target/name).write_bytes(data)
    with self.assertRaises(Exception):verify(target)
if __name__=='__main__':unittest.main()
