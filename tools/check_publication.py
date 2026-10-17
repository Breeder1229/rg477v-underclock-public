"""Check the intentionally selected public source snapshot without echoing matched data."""
from pathlib import Path
import hashlib
import json
import re
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[1]
ALLOWED_TOP={'app','integration','profiles','gradle','tools','docs','.github','third-party'}
ALLOWED_ROOT={'README.md','NOTICE.md','.gitignore','.gitattributes','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat'}
PATTERNS={
    'personal_absolute_path':re.compile(rb'(?i)(?:[A-Z]:[\\/]+Users[\\/]+|/U' rb'sers/|/ho' rb'me/)[^\s\x00\"<>]{2,}'),
    'device_serial':re.compile(rb'RG477V[0-9]{6,}'),
    'private_ipv4':re.compile(rb'(?<![0-9])(?:192\.168\.[0-9]{1,3}\.[0-9]{1,3}|10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(?:1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})(?![0-9])'),
    'credential':re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)'),
    'credential_url':re.compile(rb'https?://[^\s/<>]+:[^\s/<>]+@|[?&](?:access_token|token|auth)=[A-Za-z0-9_%.-]{12,}'),
}

def selected():
    for path in sorted(ROOT.rglob('*')):
        rel=path.relative_to(ROOT)
        if any(p in {'.git','.gradle','.tools','build','__pycache__'} for p in rel.parts):continue
        if path.is_file():yield path

def inspect_bytes(name,data,findings):
    for category,pattern in PATTERNS.items():
        if pattern.search(data):findings.append({'path':name,'category':category})

def main():
    findings=[];files=[]
    for path in selected():
        rel=path.relative_to(ROOT).as_posix();parts=Path(rel).parts
        if parts[0] not in ALLOWED_TOP and rel not in ALLOWED_ROOT:
            findings.append({'path':rel,'category':'unapproved_source_path'})
        if path.suffix.lower() in {'.apk','.aab','.zip','.pk8','.pem','.keystore','.jks','.log','.exe','.dll','.dex','.class','.so'}:
            findings.append({'path':rel,'category':'unapproved_binary_or_private_artifact'})
        data=path.read_bytes();inspect_bytes(rel,data,findings)
        if path.suffix=='.jar':
            if rel!='gradle/wrapper/gradle-wrapper.jar' or hashlib.sha256(data).hexdigest()!='81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f':
                findings.append({'path':rel,'category':'unapproved_jar'})
        if path.suffix=='.md':
            for match in re.finditer(r'!?\[[^\]]*\]\(([^)\s]+)',data.decode('utf-8')):
                target=match.group(1).split('#',1)[0]
                if not target or re.match(r'[a-z]+:',target):continue
                if not (path.parent/target).resolve().is_file():
                    findings.append({'path':rel,'category':'broken_local_document_link','target':target})
        files.append(rel)
    result={'source_files_scanned':len(files),'findings':findings,'status':'passed' if not findings else 'failed',
            'scope':'Selected public snapshot only; no private history/raw archives; pattern scan is not proof of absence of all secrets.'}
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 1 if findings else 0

if __name__=='__main__':sys.exit(main())
