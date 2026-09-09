import tempfile
import unittest
import zipfile
from pathlib import Path

from patch_apk_manifest_version import rewrite_apk


class RewriteApkTest(unittest.TestCase):
    def test_versioning_preserves_runtime_services_and_every_other_resource(self):
        resources = {
            'classes.dex': b'dex payload',
            'lib/arm64-v8a/libviewer.so': b'native payload',
            'META-INF/services/io.grpc.ManagedChannelProvider': b'io.grpc.okhttp.OkHttpChannelProvider\n',
            'META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory': b'kotlinx.coroutines.android.AndroidDispatcherFactory\n',
            'META-INF/androidx.webkit_webkit.version': b'1.16.0',
            'META-INF/androidx/annotation/annotation/LICENSE.txt': b'library license',
            'META-INF/nested/ordinary.SF': b'not a root signature',
        }
        signatures = ['META-INF/MANIFEST.MF', 'META-INF/OLD.SF', 'META-INF/OLD.RSA',
                      'META-INF/OLD.DSA', 'META-INF/OLD.EC', 'META-INF/SIG-OLD', 'meta-inf/old.sf']
        with tempfile.TemporaryDirectory() as directory:
            original, result = Path(directory) / 'base.apk', Path(directory) / 'updated.apk'
            with zipfile.ZipFile(original, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'original manifest')
                for name, data in resources.items():
                    archive.writestr(name, data)
                for name in signatures:
                    archive.writestr(name, b'old signature')
            rewrite_apk(original, result, b'updated manifest')
            with zipfile.ZipFile(result) as archive:
                self.assertEqual(set(resources) | {'AndroidManifest.xml'}, set(archive.namelist()))
                self.assertEqual(b'updated manifest', archive.read('AndroidManifest.xml'))
                for name, data in resources.items():
                    self.assertEqual(data, archive.read(name), name)


if __name__ == '__main__':
    unittest.main()
