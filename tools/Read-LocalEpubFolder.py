"""Trusted local operator: scan actual EPUB metadata, map book IDs and run bounded ingestion.

Requires the local-profile API to have migrated the database and a built worker.
No HTTP endpoint accepts a filesystem path. Source EPUBs remain untouched.
"""
import argparse,hashlib,json,os,subprocess,sys,uuid
from pathlib import Path
import importlib.util

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--database',default='Ebook')
    parser.add_argument('--host',choices=['127.0.0.1','localhost'],default='127.0.0.1')
    parser.add_argument('--port',type=int,default=5432)
    parser.add_argument('--user',default='postgres')
    parser.add_argument('--psql',default=r'C:\Program Files\PostgreSQL\18\bin\psql.exe')
    parser.add_argument('--actor',type=uuid.UUID)
    parser.add_argument('--create-operator',action='store_true',help='Create a local operator identity with no usable email or login password')
    parser.add_argument('--publish-ready',action='store_true',help='Explicitly publish successful imports for this local catalog')
    parser.add_argument('--limit',type=int,default=2000)
    parser.add_argument('--storage',type=Path)
    args=parser.parse_args()
    if not 1<=args.limit<=2000:parser.error('limit must be 1..2000')
    repo=Path(__file__).resolve().parent.parent;root=args.root.resolve(strict=True)
    if not root.is_dir():parser.error('root must be a directory')
    java=Path(os.environ['JAVA_HOME'])/'bin/java.exe';jar=repo/'worker/target/worker-0.1.0-SNAPSHOT-exec.jar'
    if not java.is_file() or not jar.is_file():parser.error('Set JAVA_HOME and build worker first')
    run=repo/'var'/('folder-import-'+uuid.uuid4().hex);run.mkdir(parents=True)
    # Reuse the same bounded metadata scanner used by the local catalog; no binary or source path is sent to FE.
    script=repo/'tools/Import-LocalEpubCatalog.py'
    subprocess.run([sys.executable,str(script),'--root',str(root),'--database',args.database,'--host',args.host,'--port',str(args.port),'--user',args.user,'--psql',args.psql,'--output',str(run/'scan'),'--apply'],check=True)
    spec=importlib.util.spec_from_file_location('catalog_scan',script);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    sql=lambda query:module.psql(args,query)
    if args.actor:actor=str(args.actor)
    elif args.create_operator:
        # The reserved address fails normal email validation, and the password cannot be matched.
        # This identity is for trusted host-side service authorization/audit, never browser login.
        actor=sql("INSERT INTO users(email_normalized,password_hash,display_name,role,email_verified_at) VALUES ('local-folder@','!LOCAL_OPERATOR_NO_LOGIN','Local folder operator','ADMIN',now()) ON CONFLICT(email_normalized) DO NOTHING; SELECT id FROM users WHERE email_normalized='local-folder@' AND role='ADMIN' AND status='ACTIVE' AND password_hash='!LOCAL_OPERATOR_NO_LOGIN';").splitlines()[-1]
        actor=str(uuid.UUID(actor))
    else:parser.error('Provide --actor or explicitly use --create-operator')
    source_root=hashlib.sha256(str(root).casefold().encode()).hexdigest()
    rows=json.loads(sql("SELECT coalesce(json_agg(s),'[]') FROM (SELECT DISTINCT ON (ls.book_id) ls.book_id,ls.relative_path FROM local_epub_sources ls JOIN books b ON b.id=ls.book_id WHERE source_root='"+source_root+"' AND b.visibility='PUBLIC' AND b.deleted_at IS NULL AND b.status IN ('DRAFT','PUBLISHED') ORDER BY ls.book_id,ls.byte_size DESC,ls.relative_path) s"))
    # Start with short books so the reader can be checked immediately; maintain one source per existing book identity.
    rows.sort(key=lambda r:(root/r['relative_path']).stat().st_size)
    manifest=run/'manifest.tsv'
    for row in rows:
        if any(c in row['relative_path'] for c in '\r\n\t'):raise ValueError('Unsupported control character in source path')
    manifest.write_text(''.join(str(uuid.UUID(r['book_id']))+'\t'+r['relative_path']+'\n' for r in rows),encoding='utf-8')
    storage=(args.storage or repo/'var/storage').resolve()
    command=[str(java),'-Xmx1536m','-jar',str(jar),'--spring.profiles.active=local','--debug=false','--app.local-seed.enabled=false','--app.database-url=jdbc:postgresql://'+args.host+':'+str(args.port)+'/'+args.database+'?currentSchema=public','--app.database-username='+args.user,'--app.storage-root='+str(storage),'--app.local-import.enabled=true','--app.local-import.root='+str(root),'--app.local-import.manifest='+str(manifest),'--app.local-import.actor='+actor,'--app.local-import.limit='+str(args.limit),'--app.local-import.await-ready=true','--app.local-import.publish-ready='+str(args.publish_ready).lower()]
    with (run/'worker.log').open('w',encoding='utf-8') as log:
        process=subprocess.Popen(command,cwd=repo,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    result={'pid':process.pid,'mappedBooks':len(rows),'limit':args.limit,'publishReady':args.publish_ready,'logs':str(run/'worker.log'),'manifest':str(manifest),'storage':str(storage)}
    (run/'run.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))

if __name__=='__main__':main()
