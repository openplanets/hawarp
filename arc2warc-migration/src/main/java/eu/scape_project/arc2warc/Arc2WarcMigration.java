/*
 * Copyright 2012 The SCAPE Project Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * under the License.
 */
package eu.scape_project.arc2warc;

import eu.scape_project.arc2warc.cli.CliConfig;
import eu.scape_project.arc2warc.cli.Options;
import eu.scape_project.arc2warc.warc.FlatListArcRecordMapper;
import eu.scape_project.arc2warc.warc.WarcCreator;
import eu.scape_project.arc2warc.warc.WarcOutputFormat;
import eu.scape_project.tika_identify.tika.TikaIdentification;
import eu.scape_project.hawarp.mapreduce.ArcInputFormat;
import eu.scape_project.hawarp.mapreduce.FlatListArcRecord;
import eu.scape_project.hawarp.mapreduce.JwatArcReaderFactory;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.PosixParser;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.util.GenericOptionsParser;
import org.jwat.arc.ArcRecordBase;

import eu.scape_project.tika_identify.webarchive.PayloadContent;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import eu.scape_project.hawarp.utils.DigestUtils;
import eu.scape_project.hawarp.utils.RegexUtils;
import eu.scape_project.hawarp.utils.StringUtils;

import static eu.scape_project.tika_identify.identification.IdentificationConstants.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.hadoop.io.Text;
import org.jwat.arc.ArcReader;
import org.jwat.warc.WarcWriter;
import org.jwat.warc.WarcWriterFactory;

/**
 * ARC to WARC conversion using Hadoop. This class defines a Hadoop job that can
 * be used to convert ARC files to WARC files. A custom Hadoop input format is
 * used to read ARC files, and a custom Hadoop output format is used to create
 * output WARC files. The internal representation of a record is the ArcRecord
 * class which also contains the payload of a record. The payload is read into a
 * byte array, therefore it can hold a maximum of Integer.MAX_VALUE bytes. It is
 * important to note that, depending on the memory available in the cluster, the
 * payload size limit may be much lower.
 *
 * @author Sven Schlarb <https://github.com/shsdev>
 */
public class Arc2WarcMigration {

    private static final Log LOG = LogFactory.getLog(Arc2WarcMigration.class);

    private static CliConfig config;

    /**
     * Mapper class.
     */
    public static class Arc2WarcConversionMapper
            extends Mapper<LongWritable, ArcRecordBase, Text, FlatListArcRecord> {

        private static final Log LOG = LogFactory.getLog(Arc2WarcConversionMapper.class);

        @Override
        public void map(LongWritable key, ArcRecordBase jwatArcRecord, Mapper.Context context) throws IOException, InterruptedException {

            String filePathString = ((FileSplit) context.getInputSplit()).getPath().toString();
            boolean identify = context.getConfiguration().getBoolean("content_type_identification", false);
            if (RegexUtils.pathMatchesRegexFilter(filePathString, context.getConfiguration().get("input_path_regex_filter"))) {
                FlatListArcRecord flArcRecord = FlatListArcRecordMapper.map(filePathString, jwatArcRecord, identify);
                context.write(new Text(filePathString), flArcRecord);
            }
        }

    }

    public Arc2WarcMigration() {
    }

    public static CliConfig getConfig() {
        return config;
    }

    /**
     * Main entry point.
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        Configuration conf = new Configuration();
        // Command line interface
        config = new CliConfig();
        CommandLineParser cmdParser = new PosixParser();
        GenericOptionsParser gop = new GenericOptionsParser(conf, args);
        CommandLine cmd = cmdParser.parse(Options.OPTIONS, gop.getRemainingArgs());
        if ((args.length == 0) || (cmd.hasOption(Options.HELP_OPT))) {
            Options.exit("Usage", 0);
        } else {
            Options.initOptions(cmd, config);
        }
        if (config.isLocalTestJob()) {
            startApplication();
        } else {
            startHadoopJob(conf);
        }

    }

    /**
     * Hadoop job
     *
     * @param conf Job configuration
     * @throws java.io.IOException
     * @throws java.lang.InterruptedException
     * @throws java.lang.ClassNotFoundException
     */
    public static void startHadoopJob(Configuration conf) throws IOException, InterruptedException, ClassNotFoundException {

        Job job = new Job(conf, "arc2warc");

        // local debugging (pseudo-distributed/using local file system instead of HDFS)
        if (config.isLocal()) {
            job.getConfiguration().set("mapred.job.tracker", "local");
            job.getConfiguration().set("fs.default.name", "file:///");
        }
        if (config.isContentTypeIdentification()) {
            job.getConfiguration().setBoolean("content_type_identification", true);
        }

        job.getConfiguration().set("input_path_regex_filter", config.getInputPathRegexFilter());

        if (config.createCompressedWarc()) {
            job.getConfiguration().setBoolean("warc_compressed", true);
        }

        job.setJarByClass(Arc2WarcMigration.class);

        job.setMapperClass(Arc2WarcMigration.Arc2WarcConversionMapper.class);

        // Custom input format for ARC files
        job.setInputFormatClass(ArcInputFormat.class);
        // Custom output format for WARC files
        job.setOutputFormatClass(WarcOutputFormat.class);

        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(FlatListArcRecord.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(FlatListArcRecord.class);

        // Setting reducer to 0 means that one WARC file is created per ARC
        // file. By removing this line, the default reducer is used and
        // all the records from the WARC files are written to one huge 
        // WARC file in HDFS. The resulting WARC file could then be split in 
        // a following step.
        job.setNumReduceTasks(0);

        ArcInputFormat.addInputPath(job, new Path(config.getInputDirStr()));
        WarcOutputFormat.setOutputPath(job, new Path(config.getOutputDirStr()));
        job.waitForCompletion(true);
        System.exit(0);

    }

    public static void startApplication() throws FileNotFoundException, IOException, InterruptedException {
        long startTime = System.currentTimeMillis();
        File outDirectory = new File(config.getOutputDirStr());
        outDirectory.mkdirs();
        Arc2WarcMigration a2wm = new Arc2WarcMigration();
        a2wm.traverseDir(new File(config.getInputDirStr()));
        long elapsedTime = System.currentTimeMillis() - startTime;
        LOG.debug("Processing time (sec): " + elapsedTime / 1000F);
        System.exit(0);
    }

    /**
     * Traverse the root directory recursively
     *
     * @param dir Root directory
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void traverseDir(File dirStructItem) throws FileNotFoundException, IOException {
        if (dirStructItem.isDirectory()) {
            String[] children = dirStructItem.list();
            for (String child : children) {
                traverseDir(new File(dirStructItem, child));
            }
        } else if (!dirStructItem.isDirectory()) {
            String filePath = dirStructItem.getAbsolutePath();
            if (RegexUtils.pathMatchesRegexFilter(filePath, config.getInputPathRegexFilter())) {
                migrate(dirStructItem);
            }
        }
    }

    private void migrate(File arcFile) {
        FileInputStream fileInputStream = null;
        ArcReader reader = null;
        FileOutputStream outputStream = null;
        WarcWriter writer = null;
        try {
            fileInputStream = new FileInputStream(arcFile);
            String inputFileName = arcFile.getName();
            reader = JwatArcReaderFactory.getReader(fileInputStream);
            String warcExt = config.createCompressedWarc() ? ".warc.gz" : ".warc";
            String warcFileName = inputFileName + warcExt;
            String warcFilePath = StringUtils.normdir(config.getOutputDirStr(), warcFileName);
            
            outputStream = new FileOutputStream(new File(warcFilePath));
            writer = WarcWriterFactory.getWriter(outputStream, config.createCompressedWarc());
            WarcCreator warcCreator = new WarcCreator(writer, warcFileName);
            warcCreator.createWarcInfoRecord();
            Iterator<ArcRecordBase> arcIterator = reader.iterator();
            ArcRecordBase jwatArcRecord = null;
            while (arcIterator.hasNext()) {
                jwatArcRecord = arcIterator.next();
                if (jwatArcRecord != null) {
                    FlatListArcRecord flArcRecord
                            = FlatListArcRecordMapper.map(inputFileName,
                                    jwatArcRecord,
                                    config.isContentTypeIdentification());
                    warcCreator.createContentRecord(flArcRecord);
                }
            }
        } catch (FileNotFoundException ex) {
            LOG.error("File not found error", ex);
        } catch (IOException ex) {
            LOG.error("I/O Error", ex);
        } finally {
            try {
                if (fileInputStream != null) {
                    fileInputStream.close();
                }
                if (reader != null) {
                    reader.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException ex) {
                LOG.error("I/O Error", ex);
            }
        }
    }
}