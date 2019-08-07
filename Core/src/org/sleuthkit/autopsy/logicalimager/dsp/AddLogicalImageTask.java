/*
 * Autopsy
 *
 * Copyright 2019 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
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
 */
package org.sleuthkit.autopsy.logicalimager.dsp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.apache.commons.io.FileUtils;
import org.openide.util.NbBundle.Messages;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.casemodule.services.Blackboard;
import org.sleuthkit.autopsy.corecomponentinterfaces.DataSourceProcessorCallback;
import org.sleuthkit.autopsy.corecomponentinterfaces.DataSourceProcessorProgressMonitor;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.autopsy.ingest.ModuleDataEvent;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.TskCoreException;

/**
 * A runnable that - copy the logical image folder to a destination folder - add
 * alert.txt and users.txt files to report - add an image data source to the
 * case database.
 */
final class AddLogicalImageTask extends AddMultipleImageTask {

    private final static Logger LOGGER = Logger.getLogger(AddLogicalImageTask.class.getName());
    private final static String ALERT_TXT = "alert.txt"; //NON-NLS
    private final static String SEARCH_RESULTS_TXT = "SearchResults.txt"; //NON-NLS
    private final static String USERS_TXT = "users.txt"; //NON-NLS
    private final File src;
    private final File dest;
    private final DataSourceProcessorCallback callback;
    private final DataSourceProcessorProgressMonitor progressMonitor;
    private final Blackboard blackboard;
    private final Case currentCase;

    AddLogicalImageTask(String deviceId,
            List<String> imagePaths,
            String timeZone,
            File src, File dest,
            DataSourceProcessorProgressMonitor progressMonitor,
            DataSourceProcessorCallback callback
    ) throws NoCurrentCaseException {
        super(deviceId, imagePaths, timeZone, progressMonitor, callback);
        this.src = src;
        this.dest = dest;
        this.progressMonitor = progressMonitor;
        this.callback = callback;
        this.currentCase = Case.getCurrentCase();
        this.blackboard = this.currentCase.getServices().getBlackboard();
    }

    /**
     * Copy the src directory to dest. Add alert.txt and users.txt to the case
     * report Adds the image to the case database.
     */
    @Messages({
        "# {0} - src", "# {1} - dest", "AddLogicalImageTask.copyingImageFromTo=Copying image from {0} to {1}",
        "AddLogicalImageTask.doneCopying=Done copying",
        "# {0} - src", "# {1} - dest", "AddLogicalImageTask.failedToCopyDirectory=Failed to copy directory {0} to {1}",
        "# {0} - file", "AddLogicalImageTask.addingToReport=Adding {0} to report",
        "# {0} - file", "AddLogicalImageTask.doneAddingToReport=Done adding {0} to report",
        "AddLogicalImageTask.addingInterestingFiles=Adding search results as intersting files",
        "AddLogicalImageTask.doneAddingInterestingFiles=Done adding search results as intersting files",
        "# {0} - searchResults.txt", "# {1} - alert.txt", "# {2} - directory", "AddLogicalImageTask.cannotFindFiles=Cannot find {0} or {1} in {2}",
        "# {0} - reason", "AddLogicalImageTask.failedToAddInterestingFiles=Failed to add interesting files: {0}"
    })
    @Override
    public void run() {
        List<String> errorList = new ArrayList<>();
        List<Content> emptyDataSources = new ArrayList<>();

        try {
            progressMonitor.setProgressText(Bundle.AddLogicalImageTask_copyingImageFromTo(src.toString(), dest.toString()));
            FileUtils.copyDirectory(src, dest);
            progressMonitor.setProgressText(Bundle.AddLogicalImageTask_doneCopying());
        } catch (IOException ex) {
            // Copy directory failed
            String msg = Bundle.AddLogicalImageTask_failedToCopyDirectory(src.toString(), dest.toString());
            errorList.add(msg);
            LOGGER.log(Level.SEVERE, String.format("Failed to copy directory %s to %s", src.toString(), dest.toString()), ex); // NON-NLS
            callback.done(DataSourceProcessorCallback.DataSourceProcessorResult.CRITICAL_ERRORS, errorList, emptyDataSources);
            return;
        }

        // Add the SearchResults.txt (or alert.txt for backward compatibility) and users.txt to the case report
        String resultsFilename;
        if (Paths.get(dest.toString(), SEARCH_RESULTS_TXT).toFile().exists()) {
            resultsFilename = SEARCH_RESULTS_TXT;
        } else if (Paths.get(dest.toString(), ALERT_TXT).toFile().exists()) {
            resultsFilename = ALERT_TXT;
        } else {
            errorList.add(Bundle.AddLogicalImageTask_cannotFindFiles(SEARCH_RESULTS_TXT, ALERT_TXT, dest.toString()));
            callback.done(DataSourceProcessorCallback.DataSourceProcessorResult.CRITICAL_ERRORS, errorList, emptyDataSources);
            return;            
        }
        progressMonitor.setProgressText(Bundle.AddLogicalImageTask_addingToReport(resultsFilename));
        String status = addReport(Paths.get(dest.toString(), resultsFilename), resultsFilename + " " + src.getName());
        if (status != null) {
            errorList.add(status);
            callback.done(DataSourceProcessorCallback.DataSourceProcessorResult.CRITICAL_ERRORS, errorList, emptyDataSources);
            return;
        }
        progressMonitor.setProgressText(Bundle.AddLogicalImageTask_doneAddingToReport(resultsFilename));

        progressMonitor.setProgressText(Bundle.AddLogicalImageTask_addingToReport(USERS_TXT));
        status = addReport(Paths.get(dest.toString(), USERS_TXT), USERS_TXT + " " + src.getName());
        if (status != null) {
            errorList.add(status);
            callback.done(DataSourceProcessorCallback.DataSourceProcessorResult.CRITICAL_ERRORS, errorList, emptyDataSources);
            return;
        }
        progressMonitor.setProgressText(Bundle.AddLogicalImageTask_doneAddingToReport(USERS_TXT));

        super.run();
        
        try {
            progressMonitor.setProgressText(Bundle.AddLogicalImageTask_addingInterestingFiles());
            addInterestingFiles(src, Paths.get(dest.toString(), resultsFilename));
            progressMonitor.setProgressText(Bundle.AddLogicalImageTask_doneAddingInterestingFiles());
        } catch (IOException | TskCoreException ex) {
            errorList.add(Bundle.AddLogicalImageTask_failedToAddInterestingFiles(ex.getMessage()));
            LOGGER.log(Level.SEVERE, "Failed to add interesting files", ex); // NON-NLS
            callback.done(DataSourceProcessorCallback.DataSourceProcessorResult.NONCRITICAL_ERRORS, errorList, emptyDataSources);
        }
    }

    /**
     * Add a file specified by the reportPath to the case report.
     *
     * @param reportPath Path to the report to be added
     * @param reportName Name associated the report
     *
     * @returns null if success, or exception message if failure
     *
     */
    @Messages({
        "# {0} - file", "# {1} - exception message", "AddLogicalImageTask.failedToAddReport=Failed to add report {0}. Reason= {1}"
    })
    private String addReport(Path reportPath, String reportName) {
        if (!reportPath.toFile().exists()) {
            return null; // if the reportPath doesn't exist, just ignore it.
        }
        try {
            Case.getCurrentCase().addReport(reportPath.toString(), "LogicalImager", reportName); //NON-NLS
            return null;
        } catch (TskCoreException ex) {
            String msg = Bundle.AddLogicalImageTask_failedToAddReport(reportPath.toString(), ex.getMessage());
            LOGGER.log(Level.SEVERE, String.format("Failed to add report %s. Reason= %s", reportPath.toString(), ex.getMessage()), ex); // NON-NLS
            return msg;
        }
    }

    @Messages({
        "# {0} - line number", "# {1} - fields length", "# {2} - expected length", "AddLogicalImageTask.notEnoughFields=File does not contain enough fields at line {0}, got {1}, expecting {2}"
    })
    private void addInterestingFiles(File src, Path resultsPath) throws IOException, TskCoreException {
        try (BufferedReader br = new BufferedReader(new FileReader(resultsPath.toFile()))) {
            String line;
            br.readLine(); // skip the header line
            int lineNumber = 2;
            while ((line = br.readLine()) != null) {
                String[] fields = line.split("\t", -1); // NON-NLS
                if (fields.length != 9) {
                    throw new IOException(Bundle.AddLogicalImageTask_notEnoughFields(lineNumber, fields.length, 9));
                }
                String vhdFilename = fields[0];
//                String fileSystemOffsetStr = fields[1];
                String fileMetaAddressStr = fields[2];
//                String extractStatusStr = fields[3];
                String ruleSetName = fields[4];
                String ruleName = fields[5];
//                String description = fields[6];
                String filename = fields[7];
//                String parentPath = fields[8];
                
                String dataSourceObjId = findDataSourceObjId(src, vhdFilename);
                
                String query = String.format("data_source_obj_id = '%s' AND meta_addr = '%s' AND name = '%s'", // NON-NLS
                        dataSourceObjId, fileMetaAddressStr, filename);
                List<AbstractFile> matchedFiles = Case.getCurrentCase().getSleuthkitCase().findAllFilesWhere(query);
                for (AbstractFile file : matchedFiles) {
                    addInterestingFile(file, ruleSetName, ruleName);
                }
                lineNumber++;   
            }
        }
    }

    @Messages({
        "# {0} - target image path", "AddLogicalImageTask.cannotFindDataSourceObjId=Cannot find obj_id in tsk_image_names for {0}"
    })
    private String findDataSourceObjId(File src, String vhdFilename) throws TskCoreException {
        String targetImagePath = Paths.get(src.toString(), vhdFilename).toString();
        Map<Long, List<String>> imagePaths = currentCase.getSleuthkitCase().getImagePaths();
        for (Map.Entry<Long, List<String>> entry : imagePaths.entrySet()) {
            Long key = entry.getKey();
            List<String> names = entry.getValue();
            for (String name : names) {
                if (name.equals(targetImagePath)) {
                    return key.toString();
                }
            }
        }
        throw new TskCoreException(Bundle.AddLogicalImageTask_cannotFindDataSourceObjId(targetImagePath));
    }

    private void addInterestingFile(AbstractFile file, String ruleSetName, String ruleName) throws TskCoreException {
        Collection<BlackboardAttribute> attributes = new ArrayList<>();
        BlackboardAttribute setNameAttribute = new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_SET_NAME, ruleSetName, ruleName);
        attributes.add(setNameAttribute);
        org.sleuthkit.datamodel.Blackboard tskBlackboard = Case.getCurrentCase().getSleuthkitCase().getBlackboard();
        if (!tskBlackboard.artifactExists(file, BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT, attributes)) {
            BlackboardArtifact artifact = file.newArtifact(BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT);
            artifact.addAttributes(attributes);
            try {
                // index the artifact for keyword search
                blackboard.indexArtifact(artifact);
            } catch (Blackboard.BlackboardException ex) {
                LOGGER.log(Level.SEVERE, "Unable to index blackboard artifact " + artifact.getArtifactID(), ex); //NON-NLS
            }
            IngestServices.getInstance().fireModuleDataEvent(new ModuleDataEvent("LogicalImager", // NON-NLS
                    BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT, Collections.singletonList(artifact)));
        }
    }
}
