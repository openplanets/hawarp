/*
 *  Copyright 2012 The SCAPE Project Consortium.
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  under the License.
 */
package eu.scape_project.droid_identify.hadoop;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Command line interface options.
 * 
 * @author Sven Schlarb https://github.com/shsdev
 * @version 0.1
 */
public class HadoopJobOptions {

    private static final Log LOG = LogFactory.getLog(HadoopJobOptions.class);
    // Statics to set up command line arguments
    public static final String HELP_FLG = "h";
    public static final String HELP_OPT = "help";
    public static final String HELP_OPT_DESC = "print this message.";

    public static final String DIR_FLG = "d";
    public static final String DIR_OPT = "dir";
    public static final String DIR_OPT_DESC = "HDFS directory containing text file(s) with file references.";
    
    public static final String NAME_FLG = "n";
    public static final String NAME_OPT = "name";
    public static final String NAME_OPT_DESC = "Job name.";
    
    public static org.apache.commons.cli.Options OPTIONS = new org.apache.commons.cli.Options();
    public static final String USAGE = "hadoop jar "
            + "target/droid-identify-hadoopjob-1.0-jar-with-dependencies.jar "
            + "-n job_name -d /path/to/hdfs/input/directory";
    static {
        OPTIONS.addOption(HELP_FLG, HELP_OPT, false, HELP_OPT_DESC);
        OPTIONS.addOption(DIR_FLG, DIR_OPT, true, DIR_OPT_DESC);
        OPTIONS.addOption(NAME_FLG, NAME_OPT, true, NAME_OPT_DESC);
    }
    
    public static void initOptions(CommandLine cmd, HadoopJobCliConfig pc) {
        // dir
        String dirStr;
        if (!(cmd.hasOption(DIR_OPT) && cmd.getOptionValue(DIR_OPT) != null)) {
            exit("No directory given.", 1);
        } else {
            dirStr = cmd.getOptionValue(DIR_OPT);
            pc.setDirStr(dirStr);
            LOG.info("Directory: " + dirStr);
        }
        // name
        String nameStr;
        if (!(cmd.hasOption(NAME_OPT) && cmd.getOptionValue(NAME_OPT) != null)) {
            //exit("No hadoop job name given.", 1);
        } else {
            nameStr = cmd.getOptionValue(NAME_OPT);
            pc.setHadoopJobName(nameStr);
            LOG.info("Hadoop job name: " + nameStr);
        }
    }

    public static void exit(String msg, int status) {
        if (status > 0) {
            LOG.error(msg);
        } else {
            LOG.info(msg);
        }
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(USAGE, OPTIONS, true);
        System.exit(status);
    }
}
