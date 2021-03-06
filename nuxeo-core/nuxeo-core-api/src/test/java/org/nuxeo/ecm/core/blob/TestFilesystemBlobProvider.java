/*
 * (C) Copyright 2015-2017 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.blob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.HotDeployer;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import org.nuxeo.runtime.test.runner.LogCaptureFeature;
import org.nuxeo.runtime.test.runner.LogFeature;
import org.nuxeo.runtime.test.runner.RuntimeHarness;

@RunWith(FeaturesRunner.class)
@Features({ BlobManagerFeature.class, LogFeature.class, LogCaptureFeature.class })
@LocalDeploy("org.nuxeo.ecm.core.api:OSGI-INF/test-fs-blobprovider.xml")
public class TestFilesystemBlobProvider {

    private static final String CONTENT = "hello";

    private static final String CONTENT_MD5 = "5d41402abc4b2a76b9719d911017c592";

    private static final String PROVIDER_ID = "testfs";

    @Inject
    protected RuntimeHarness harness;

    @Inject
    protected BlobManager blobManager;

    @Inject
    protected HotDeployer deployer;

    @Inject
    protected LogFeature logFeature;

    @Inject
    protected LogCaptureFeature.Result logCaptureResult;

    protected Path tmpFile;

    protected String tmpFilePath;

    @Before
    public void setUp() throws Exception {
        tmpFile = Framework.createTempFilePath("tmp", ".txt");
        try (OutputStream out = Files.newOutputStream(tmpFile)) {
            InputStream in = new ByteArrayInputStream(CONTENT.getBytes());
            IOUtils.copy(in, out);
        }
        tmpFilePath = tmpFile.toString();
    }

    @After
    public void tearDown() throws Exception {
        if (tmpFile != null && Files.exists(tmpFile)) {
            Files.delete(tmpFile);
        }
    }

    @Test
    public void testSupportsUserUpdate() throws Exception {
        BlobProvider blobProvider = blobManager.getBlobProvider(PROVIDER_ID);
        assertFalse(blobProvider.supportsUserUpdate());

        // check that we can allow user updates of blobs by configuration
        deployer.deploy("org.nuxeo.ecm.core.api.tests:OSGI-INF/test-fs-blobprovider-override.xml");

        try {
            blobProvider = blobManager.getBlobProvider(PROVIDER_ID);
            assertTrue(blobProvider.supportsUserUpdate());
        } finally {
            harness.undeployContrib("org.nuxeo.ecm.core.api.tests", "OSGI-INF/test-fs-blobprovider-override.xml");
        }
    }

    @Test
    public void testRead() throws Exception {
        String key = PROVIDER_ID + ":" + tmpFilePath;

        BlobInfo blobInfo = new BlobInfo();
        blobInfo.key = key;
        BlobProvider blobProvider = blobManager.getBlobProvider(PROVIDER_ID);
        ManagedBlob blob = (ManagedBlob) blobProvider.readBlob(blobInfo);
        assertNotNull(blob);
        assertEquals(key, blob.getKey());
        try (InputStream in = blob.getStream()) {
            assertEquals(CONTENT, IOUtils.toString(in));
        }

        // same with explicit blob
        blobInfo.key = tmpFilePath;
        blobInfo.mimeType = "text/plain";
        blob = ((FilesystemBlobProvider) blobProvider).createBlob(blobInfo);
        assertEquals(key, blob.getKey());
        assertEquals(tmpFile.getFileName().toString(), blob.getFilename());
        assertEquals("text/plain", blob.getMimeType());
        assertEquals(CONTENT.length(), blob.getLength());
        assertEquals(CONTENT_MD5, blob.getDigest());
        try (InputStream in = blob.getStream()) {
            assertEquals(CONTENT, IOUtils.toString(in));
        }
    }

    @Test
    @LogCaptureFeature.FilterOn(logLevel = "ERROR")
    public void testReadNotFound() throws Exception {
        String path = "/NO_SUCH_FILE_EXISTS";
        assertFalse(Files.exists(Paths.get(path)));
        String key = PROVIDER_ID + ":" + path;

        BlobInfo blobInfo = new BlobInfo();
        blobInfo.key = key;
        BlobProvider blobProvider = blobManager.getBlobProvider(PROVIDER_ID);
        ManagedBlob blob = (ManagedBlob) blobProvider.readBlob(blobInfo);
        byte[] bytes;
        logFeature.hideErrorFromConsoleLog();
        try (InputStream in = blob.getStream()) {
            bytes = IOUtils.toByteArray(in);
        } finally {
            logFeature.restoreConsoleLog();
        }
        assertEquals(0, bytes.length);
        List<LoggingEvent> caughtEvents = logCaptureResult.getCaughtEvents();
        assertEquals(1, caughtEvents.size());
        assertEquals("Failed to access file: testfs:/NO_SUCH_FILE_EXISTS", caughtEvents.get(0).getRenderedMessage());
    }

    @Test
    public void testRoot() throws Exception {
        String PROVIDER_ID2 = "testfs2";
        BlobProviderDescriptor descr = new BlobProviderDescriptor();
        descr.klass = FilesystemBlobProvider.class;
        descr.name = PROVIDER_ID2;
        descr.properties = Collections.singletonMap(FilesystemBlobProvider.ROOT_PROP, tmpFile.getParent().toString());
        ((BlobManagerComponent) blobManager).registerBlobProvider(descr);
        try {
            // use relative path under root
            String key = PROVIDER_ID2 + ":" + tmpFile.getFileName().toString();
            BlobInfo blobInfo = new BlobInfo();
            blobInfo.key = key;
            BlobProvider blobProvider = blobManager.getBlobProvider(PROVIDER_ID);
            ManagedBlob blob = (ManagedBlob) blobProvider.readBlob(blobInfo);
            assertNotNull(blob);
            assertEquals(key, blob.getKey());
            try (InputStream in = blob.getStream()) {
                assertEquals(CONTENT, IOUtils.toString(in));
            }
        } finally {
            ((BlobManagerComponent) blobManager).unregisterBlobProvider(descr);
        }
    }

    @Test
    @LogCaptureFeature.FilterOn(logLevel = "ERROR")
    public void testIllegalPath() throws Exception {
        String illegalPath = "../foo";

        String PROVIDER_ID2 = "testfs2";
        BlobProviderDescriptor descr = new BlobProviderDescriptor();
        descr.klass = FilesystemBlobProvider.class;
        descr.name = PROVIDER_ID2;
        descr.properties = Collections.singletonMap(FilesystemBlobProvider.ROOT_PROP, tmpFile.getParent().toString());
        ((BlobManagerComponent) blobManager).registerBlobProvider(descr);
        try {
            String key = PROVIDER_ID2 + ":" + illegalPath;
            BlobInfo blobInfo = new BlobInfo();
            blobInfo.key = key;
            BlobProvider blobProvider = blobManager.getBlobProvider(PROVIDER_ID);
            ManagedBlob blob = (ManagedBlob) blobProvider.readBlob(blobInfo);
            assertNotNull(blob);
            assertEquals(key, blob.getKey());
            byte[] bytes;
            logFeature.hideErrorFromConsoleLog();
            try (InputStream in = blob.getStream()) {
                bytes = IOUtils.toByteArray(in);
            } finally {
                logFeature.restoreConsoleLog();
            }
            assertEquals(0, bytes.length);
            List<LoggingEvent> caughtEvents = logCaptureResult.getCaughtEvents();
            assertEquals(1, caughtEvents.size());
            assertEquals("Failed to access file: testfs2:../foo", caughtEvents.get(0).getRenderedMessage());
        } finally {
            ((BlobManagerComponent) blobManager).unregisterBlobProvider(descr);
        }
    }

}
