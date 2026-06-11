import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('epub_import', Path(__file__).with_name('Import-LocalEpubCatalog.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MetadataScanTest(unittest.TestCase):
    def fixture(self, root, metadata, mimetype=b'application/epub+zip'):
        path = root / 'Genre' / 'Book - Author.epub'
        path.parent.mkdir(exist_ok=True)
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('mimetype', mimetype)
            archive.writestr('META-INF/container.xml', '<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>')
            archive.writestr('OEBPS/content.opf', metadata)
        return path

    def test_metadata_is_plain_text_and_does_not_claim_reader_readiness(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = self.fixture(root, '<package><metadata><title>OPF Book</title><creator>Translator</creator><description>&lt;p&gt;Introduction&lt;/p&gt;&lt;script&gt;bad()&lt;/script&gt;</description></metadata></package>')
            result = module.inspect(path, root)
            self.assertEqual(('Book', 'Author', 'Genre', 'Introduction'), tuple(result[k] for k in ('title', 'author', 'category', 'description')))
            self.assertEqual('Genre/Book - Author.epub', result['path'])
            self.assertNotIn('readerAvailable', result)
            self.assertNotIn('state', result)

    def test_rejects_non_epub_and_entity_definitions(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = self.fixture(root, '<package/>', b'not-an-epub')
            with self.assertRaises(ValueError):
                module.inspect(path, root)
            path = self.fixture(root, '<!DOCTYPE package [<!ENTITY leak SYSTEM "file:///test">]><package/>')
            with self.assertRaises(ValueError):
                module.inspect(path, root)

    def test_identity_preserves_distinct_authors(self):
        self.assertEqual(module.fold('ĐƯỜNG ĐẾN'), module.fold('duong den'))
        self.assertNotEqual(module.identity('book', 'title|author-a'), module.identity('book', 'title|author-b'))


if __name__ == '__main__':
    unittest.main()
