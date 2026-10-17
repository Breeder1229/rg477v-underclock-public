"""Extract only the two pinned runtime assets from a locally supplied release APK."""
from pathlib import Path
import argparse
import hashlib
import zipfile

PAYLOADS = {
    'control-center.apk': '434246eaec4f417d2ff1ed04c39063ec5ef0ffdf8a7fb0b1f28a9c72482a7ab0',
    'stock-control-center.apk': '37b7cb60e393176c2208d1b9cf1666d14b9df89e9b8367d28eb0f4bddf85877a',
}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-apk',type=Path,required=True)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    output=root/'app/build/generated/controlcenter'
    checked={}
    with zipfile.ZipFile(args.release_apk) as archive:
        for name,expected in PAYLOADS.items():
            data=archive.read('assets/'+name)
            if hashlib.sha256(data).hexdigest()!=expected:
                raise ValueError('Release payload verification failed: '+name)
            checked[name]=data
    output.mkdir(parents=True,exist_ok=True)
    for name,data in checked.items():
        (output/name).write_bytes(data)
    print('Verified and prepared two runtime assets.')

if __name__=='__main__':main()
