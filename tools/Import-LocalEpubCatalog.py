"""Inspect a local EPUB collection and optionally list its metadata in PostgreSQL.

No file extraction, reader artifacts, READY versions or publication are performed.
Dry run is the default. PostgreSQL credentials come from normal libpq settings.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
from html import unescape
from html.parser import HTMLParser
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import unicodedata
import uuid
import xml.etree.ElementTree as ET
import zipfile


class PlainText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.hidden += 1
        if tag in ('p', 'div', 'br', 'li') and not self.hidden:
            self.parts.append(' ')

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.hidden = max(0, self.hidden - 1)
        if not self.hidden:
            self.parts.append(' ')

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def plain(value):
    parser = PlainText()
    parser.feed(value or '')
    return ' '.join(unescape(''.join(parser.parts)).split())


def fold(value):
    value = unicodedata.normalize('NFD', value.casefold().replace('đ', 'd'))
    return ' '.join(''.join(c for c in value if not unicodedata.combining(c)).split())


def identity(kind, value):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'local-epub-catalog:' + kind + ':' + value))


def slug(value, identifier):
    stem = re.sub('[^a-z0-9]+', '-', fold(value)).strip('-')[:100].strip('-') or 'truyen'
    return stem + '-' + identifier[:8]


def read_xml(archive, name, limit):
    info = archive.getinfo(name)
    if info.file_size > limit or info.flag_bits & 1:
        raise ValueError('oversized or encrypted metadata')
    raw = archive.read(info)
    if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
        raise ValueError('XML declarations with entities are unsupported')
    return ET.fromstring(raw)


def inspect(path, root):
    relative = path.relative_to(root)
    stat = path.stat()
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > 100000:
            raise ValueError('too many archive entries')
        if archive.getinfo('mimetype').file_size > 128 or archive.read('mimetype').strip() != b'application/epub+zip':
            raise ValueError('invalid EPUB mimetype')
        container = read_xml(archive, 'META-INF/container.xml', 128 * 1024)
        entry = container.find('.//{*}rootfile')
        name = entry.get('full-path') if entry is not None else None
        if not name or PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts:
            raise ValueError('invalid OPF path')
        package = read_xml(archive, name, 2 * 1024 * 1024)
        metadata = package.find('{*}metadata')
        if metadata is None:
            raise ValueError('missing EPUB metadata')
        def values(tag):
            return [plain(''.join(node.itertext())) for node in metadata.findall('{*}' + tag)]
        titles, creators, languages, descriptions = (values(t) for t in ('title', 'creator', 'language', 'description'))
        title = titles[0] if titles else ''
        if not title:
            raise ValueError('missing EPUB title')
        # Filenames consistently use title - author; prefer their stable identity
        # across full/50-chapter variants, while retaining the OPF as metadata.
        filename = path.stem.rsplit(' - ', 1)
        filename_title = filename[0].strip()
        title = filename_title or title
        author = filename[1].strip() if len(filename) == 2 else (creators[0] if creators else 'Chưa rõ tác giả')
        language = next((v for v in languages if v.lower().startswith('vi')), languages[0] if languages else 'vi')
        description = ' '.join(descriptions)[:20000]
    return dict(title=title[:300], author=author[:160], language=language[:35],
                description=description, summary=description[:2000], category=relative.parts[0],
                path=relative.as_posix(), size=stat.st_size,
                modified=datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat())


def psql(args, sql=None, file=None):
    command = [args.psql, '-X', '-w', '-h', args.host, '-p', str(args.port), '-U', args.user,
               '-d', args.database, '-v', 'ON_ERROR_STOP=1', '-t', '-A']
    command += ['-f', str(file)] if file else ['-c', sql]
    return subprocess.run(command, check=True, capture_output=True, text=True, encoding='utf-8',
                          env={**os.environ, 'PGCLIENTENCODING': 'UTF8'}).stdout.strip()


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('var/local-epub-catalog'))
    parser.add_argument('--psql', default=r'C:\Program Files\PostgreSQL\18\bin\psql.exe')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=5432)
    parser.add_argument('--user', default='postgres')
    parser.add_argument('--database', default='Ebook')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    output = args.output.resolve()
    if not output.is_relative_to(Path(__file__).resolve().parents[1] / 'var'):
        parser.error('--output must be inside this backend repository var directory')
    output.mkdir(parents=True, exist_ok=True)
    files = sorted((p for p in root.rglob('*') if p.suffix.lower() == '.epub'), key=str)
    records, errors = [], []
    for path in files:
        try:
            # Symlinks outside the requested collection are never read.
            if not path.resolve().is_relative_to(root):
                raise ValueError('path escapes collection')
            records.append(inspect(path, root))
        except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile, RuntimeError) as error:
            errors.append(dict(path=path.relative_to(root).as_posix(), error=str(error)))
    (output / 'inventory.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
    (output / 'invalid.json').write_text(json.dumps(errors, ensure_ascii=False, indent=2), encoding='utf-8')
    # JSON manifests are a cross-check only; existence and metadata come from disk.
    manifests = []
    for manifest in root.rglob('danh_sach_epub.json'):
        content = json.loads(manifest.read_text(encoding='utf-8-sig'))
        entries = content.get('files', [])
        missing = []
        for entry in entries:
            candidate = (manifest.parent / entry['relative_path']).resolve()
            if not candidate.is_relative_to(root) or not candidate.is_file():
                missing.append(entry['relative_path'])
        manifests.append(dict(category=content.get('category'), entries=len(entries), missing=missing))
    groups = defaultdict(list)
    for record in records:
        groups[(fold(record['title']), fold(record['author']))].append(record)
    report = dict(epubFiles=len(files), validMetadata=len(records), invalid=len(errors),
                  uniqueBooks=len(groups), categories=dict(Counter(r['category'] for r in records)),
                  manifests=manifests, applied=False)
    if args.apply:
        existing = json.loads(psql(args, "SELECT coalesce(json_agg(json_build_object('id',id,'title',title,'author',author_display,'status',status)), '[]') FROM books WHERE visibility='PUBLIC'"))
        by_key = defaultdict(list)
        for row in existing:
            by_key[(fold(row['title']), fold(row['author']))].append(row)
        categories = json.loads(psql(args, "SELECT coalesce(json_agg(json_build_object('id',id,'name',name)),'[]') FROM categories"))
        category_ids = {fold(c['name']): c['id'] for c in categories}
        authors = json.loads(psql(args, "SELECT coalesce(json_agg(json_build_object('id',id,'name',name)),'[]') FROM authors"))
        author_ids = {fold(a['name']): a['id'] for a in authors}
        source_root = hashlib.sha256(str(root).casefold().encode('utf-8')).hexdigest()
        statements = ['BEGIN;', 'SET LOCAL standard_conforming_strings=on;',
                      'CREATE TEMP TABLE affected_books(id uuid PRIMARY KEY);',
                      'INSERT INTO affected_books SELECT DISTINCT book_id FROM local_epub_sources WHERE source_root=' + literal(source_root) + ';',
                      'DELETE FROM local_epub_sources WHERE source_root=' + literal(source_root) + ';']
        reused, created, blocked = 0, 0, 0
        for key, variants in groups.items():
            if len(by_key[key]) > 1:
                raise ValueError('ambiguous existing title/author; resolve before importing: ' + variants[0]['title'])
            # Prefer the richest description across the actual file variants.
            record = max(variants, key=lambda r: len(r['description']))
            if by_key[key]:
                if by_key[key][0]['status'] not in ('DRAFT', 'PUBLISHED'):
                    blocked += 1
                    continue
                book_id = by_key[key][0]['id']
                reused += 1
                statements.append("UPDATE books SET catalog_listed=true,summary=CASE WHEN summary='' THEN %s ELSE summary END,description=CASE WHEN description='' THEN %s ELSE description END,revision=revision+1 WHERE id=%s AND visibility='PUBLIC' AND status='DRAFT' AND (NOT catalog_listed OR (summary='' AND %s<>'') OR (description='' AND %s<>''));" % tuple(literal(v) for v in (record['summary'], record['description'], book_id, record['summary'], record['description'])))
            else:
                book_id = identity('book', '|'.join(key))
                created += 1
                statements.append("INSERT INTO books(id,visibility,title,author_display,slug,summary,description,language,catalog_listed) VALUES (%s,'PUBLIC',%s,%s,%s,%s,%s,%s,true) ON CONFLICT(id) DO NOTHING;" % tuple(literal(v) for v in (book_id, record['title'], record['author'], slug(record['title'], book_id), record['summary'], record['description'], record['language'])))
            statements.append('INSERT INTO affected_books VALUES (' + literal(book_id) + ') ON CONFLICT DO NOTHING;')
            author_key = fold(record['author'])
            if author_key not in author_ids:
                author_ids[author_key] = identity('author', author_key)
                statements.append('INSERT INTO authors(id,name,slug) VALUES (%s,%s,%s) ON CONFLICT(id) DO NOTHING;' % tuple(literal(v) for v in (author_ids[author_key], record['author'], slug(record['author'], author_ids[author_key]))))
            statements.append('INSERT INTO book_authors(book_id,author_id,position) SELECT %s,%s,coalesce((SELECT max(position)+1 FROM book_authors WHERE book_id=%s),0) WHERE NOT EXISTS(SELECT 1 FROM book_authors WHERE book_id=%s AND author_id=%s) ON CONFLICT DO NOTHING;' % tuple(literal(v) for v in (book_id, author_ids[author_key], book_id, book_id, author_ids[author_key])))
            for category in sorted({r['category'] for r in variants}):
                category_key = fold(category)
                if category_key not in category_ids:
                    category_ids[category_key] = identity('category', category_key)
                    statements.append("INSERT INTO categories(id,name,slug,group_name) VALUES (%s,%s,%s,'STORY') ON CONFLICT(id) DO NOTHING;" % tuple(literal(v) for v in (category_ids[category_key], category, slug(category, category_ids[category_key]))))
                statements.append('INSERT INTO book_categories(book_id,category_id) VALUES (%s,%s) ON CONFLICT DO NOTHING;' % (literal(book_id), literal(category_ids[category_key])))
            for variant in variants:
                statements.append('INSERT INTO local_epub_sources(source_root,relative_path,book_id,byte_size,modified_at) VALUES (%s,%s,%s,%d,%s);' % (literal(source_root), literal(variant['path']), literal(book_id), variant['size'], literal(variant['modified'])))
        statements += ["UPDATE books b SET catalog_listed=EXISTS(SELECT 1 FROM local_epub_sources s WHERE s.book_id=b.id) WHERE b.id IN (SELECT id FROM affected_books) AND b.visibility='PUBLIC' AND b.status='DRAFT';", 'COMMIT;']
        sql_file = output / 'import.sql'
        sql_file.write_text('\n'.join(statements), encoding='utf-8')
        psql(args, file=sql_file)
        report.update(applied=True, reusedBooks=reused, newBooks=created, skippedHiddenOrDeleted=blocked)
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True))


if __name__ == '__main__':
    main()
