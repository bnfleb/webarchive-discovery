package uk.bl.wa.hadoop.mapreduce.lib;

/*
 * #%L
 * warc-hadoop-recordreaders
 * %%
 * Copyright (C) 2013 - 2018 The webarchive-discovery project contributors
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.apache.log4j.Logger;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.arc.ARCReader;
import org.archive.io.warc.WARCReader;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.resourceindex.cdx.format.CDXFormat;
import org.archive.wayback.resourceindex.cdx.format.CDXFormatException;
import org.archive.wayback.resourcestore.indexer.ArcIndexer;
import org.archive.wayback.resourcestore.indexer.WarcIndexer;

import uk.bl.wa.hadoop.mapreduce.cdx.CaptureSearchResultIterator;

public class DereferencingArchiveToCDXRecordReader<Key extends WritableComparable<?>, Value extends Writable>
        extends RecordReader<Text, Text> {

    public static final String CDX_09 = " CDX N b a m s k r V g";
    public static final String CDX_10 = " CDX A b a m s k r M V g";
    public static final String CDX_11 = " CDX N b a m s k r M S V g";

    private static final Logger LOGGER = Logger
            .getLogger(DereferencingArchiveToCDXRecordReader.class.getName());
    private LineRecordReader internal = new LineRecordReader();
    private FSDataInputStream datainputstream;
    private FileSystem filesystem;
    private ArchiveReader arcreader;
    private Iterator<CaptureSearchResult> archiveIterator;
    private Iterator<String> cdxlines;
    private WarcIndexer warcIndexer = new WarcIndexer();
    private ArcIndexer arcIndexer = new ArcIndexer();
    private Text key;
    private Text value;
    private CDXFormat cdxFormat;
    private boolean hdfs;
    private int gIndex = -1;
    private String metaTag;
    private int MIndex = -1;
    private HashMap<String, String> warcArkLookup = new HashMap<String, String>();

    @Override
    public void initialize(InputSplit split, TaskAttemptContext context)
            throws IOException, InterruptedException {
        Configuration conf = context.getConfiguration();
        FileSplit fileSplit = (FileSplit) split;
        //
        this.hdfs = Boolean.parseBoolean(conf.get("cdx.hdfs", "false"));
        this.filesystem = fileSplit.getPath().getFileSystem(conf);
        String format = conf.get("cdx.format", CDX_10);
        try {
            this.cdxFormat = new CDXFormat(format);
        } catch (CDXFormatException e) {
            LOGGER.error("initialize(): " + e.getMessage());
        }
        //
        String[] parts = format.substring(5).split(" ");
        for (int i = 0; i < parts.length; i++) {
            if ("g".equals(parts[i])) {
                gIndex = i;
            }
            if ("M".equals(parts[i])) {
                MIndex = i;
            }
        }
        internal.initialize(split, context);
        this.getLookup(conf);
        metaTag = conf.get("cdx.metatag");
        //
        // warcIndexer.setProcessAll(true);
        //
        LOGGER.info("Initialised with format:" + format);
    }

    private void getLookup(Configuration conf) {
        try {
            URI[] uris = DistributedCache.getCacheFiles(conf);
            if (uris != null) {
                for (URI uri : uris) {
                    FSDataInputStream input = this.filesystem
                            .open(new Path(uri.getPath()));
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(input));
                    String line;
                    String[] values;
                    while ((line = reader.readLine()) != null) {
                        values = line.split("\\s+");
                        warcArkLookup.put(values[0], values[1]);
                    }
                    System.out.println("Added " + warcArkLookup.size()
                            + " entries to ARK lookup.");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        if (datainputstream != null) {
            try {
                datainputstream.close();
            } catch (IOException e) {
                System.err.println("close(): " + e.getMessage());
            }
        }
        try {
            internal.close();
        } catch (IOException e) {
            LOGGER.error("close(): " + e.getMessage());
        }
    }

    @Override
    public float getProgress() throws IOException {
        return internal.getProgress();
    }

    @Override
    public boolean nextKeyValue() {
        if (this.key == null) {
            this.key = new Text();
        }
        if (this.value == null) {
            this.value = new Text();
        }
        String line;
        while (true) {
            try {
                if (cdxlines != null && cdxlines.hasNext()) {
                    if (this.hdfs && this.gIndex > -1) {
                        line = hdfsPath(cdxlines.next(),
                                this.internal.getCurrentValue().toString());
                    } else {
                        line = cdxlines.next();
                    }
                    if (metaTag != null && MIndex != -1)
                        line = setMetaTag(line);
                    this.key.set(line);
                    this.value.set(line);
                    return true;
                } else {
                    if (this.internal.nextKeyValue()) {
                        Path path = new Path(
                                this.internal.getCurrentValue().toString());
                        datainputstream = this.filesystem.open(path);
                        arcreader = ArchiveReaderFactory.get(path.getName(),
                                datainputstream, true);
                        arcreader.setStrict(false);
                        if (path.getName().matches("^.+\\.warc(\\.gz)?$")) {
                            archiveIterator = warcIndexer
                                    .iterator((WARCReader) arcreader);
                        } else {
                            archiveIterator = arcIndexer
                                    .iterator((ARCReader) arcreader);
                        }
                        // Dedicated iterator to attempt to determine compressed
                        // record length
                        long fileLength = this.filesystem.getFileStatus(path)
                                .getLen();
                        cdxlines = new CaptureSearchResultIterator(
                                archiveIterator, cdxFormat, fileLength);
                        LOGGER.info("Started reader on " + path.getName());
                    } else {
                        return false;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("nextKeyValue: " + e.getMessage());
            }
        }
    }

    /*
     * So, to get the compressed length, I need to iterate the ARC/WARC reader
     * myself, always getting the current and the next values, and using the
     * offsets of each. i.e. we'd store the current and next, not just the
     * current, and swap them as part of the 'get next'
     */

    @Override
    public Text getCurrentKey() throws IOException, InterruptedException {
        return this.key;
    }

    @Override
    public Text getCurrentValue() throws IOException, InterruptedException {
        return this.value;
    }

    private String hdfsPath(String cdx, String path) throws URISyntaxException {
        String[] fields = cdx.split(" ");
        if (warcArkLookup.size() != 0) {
            fields[gIndex] = warcArkLookup.get(fields[gIndex]) + "#"
                    + fields[gIndex];
        } else {
            fields[gIndex] = path;
        }
        return StringUtils.join(fields, " ");
    }

    /**
     * We use the "meta tags" field to identify the CDX's licence.
     * @param cdx
     * @return
     */
    private String setMetaTag(String cdx) {
        String[] fields = cdx.split(" ");
        if (fields[MIndex].equals("-")) {
            fields[MIndex] = metaTag;
        } else {
            fields[MIndex] = metaTag + fields[MIndex];
        }
        return StringUtils.join(fields, " ");
    }
}