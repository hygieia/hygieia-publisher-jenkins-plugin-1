package jenkins.plugins.hygieia;

import com.capitalone.dashboard.misc.HygieiaException;
import com.capitalone.dashboard.model.BuildStage;
import com.capitalone.dashboard.model.BuildStatus;
import com.capitalone.dashboard.request.BuildDataCreateRequest;
import com.capitalone.dashboard.request.CodeQualityCreateRequest;
import com.capitalone.dashboard.request.GenericCollectorItemCreateRequest;
import com.capitalone.dashboard.response.BuildDataCreateResponse;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hygieia.builder.BuildBuilder;
import hygieia.builder.GenericCollectorItemBuilder;
import hygieia.builder.SonarBuilder;
import hygieia.utils.HygieiaUtils;
import jenkins.model.Jenkins;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.json.simple.parser.ParseException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Extension
public class HygieiaGlobalListener extends RunListener<Run<?, ?>> {

    public static final String WFAPI_DESCRIBE = "/wfapi/describe";
    public static final String FAILED = "FAILED";

    public HygieiaGlobalListener() {
        super();
    }

    @Override
    public void onStarted(Run run, TaskListener listener) {
        super.onStarted(run, listener);
        HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor = getDescriptor();
        boolean showConsoleOutput = hygieiaGlobalListenerDescriptor.isShowConsoleOutput();
        boolean publish = hygieiaGlobalListenerDescriptor.isHygieiaPublishBuildDataGlobal()
                || hygieiaGlobalListenerDescriptor.isHygieiaPublishSonarDataGlobal()
                || CollectionUtils.isNotEmpty(hygieiaGlobalListenerDescriptor.getHygieiaPublishGenericCollectorItems());
        String rawApiEndopints = StringUtils.trimToEmpty(hygieiaGlobalListenerDescriptor.getHygieiaAPIUrl());
        List<String> apiEndpints = Arrays.asList(rawApiEndopints.split(HygieiaUtils.SEPERATOR));

        if (CollectionUtils.isEmpty(apiEndpints)) {
            if (showConsoleOutput) { listener.getLogger().println("Hygieia: Skipping Automatic publish to Hygieia as no service endpoints were configured. "); }
            return;
        }

        if(!publish) {
            if (showConsoleOutput) { listener.getLogger().println("Hygieia: Skipping Automatic publish to Hygieia as publish is disabled in Global Configuration. "); }
            return;
        }
        //publish the build started event
        final long startTime = run.getStartTimeInMillis();
        int index = 0;
        for (String apiEndPoint : apiEndpints) {
            if (StringUtils.isEmpty(apiEndPoint)) { continue; }
            HygieiaService hygieiaService = getHygieiaService(hygieiaGlobalListenerDescriptor, apiEndPoint);
            BuildDataCreateRequest buildRequest = new BuildDataCreateRequest();
            buildRequest.setJobName(HygieiaUtils.getJobPath(run));
            String buildUrl = HygieiaUtils.getBuildUrl(run);
            buildRequest.setBuildUrl(buildUrl);
            buildRequest.setJobUrl(HygieiaUtils.getJobUrl(run));
            buildRequest.setInstanceUrl(HygieiaUtils.getInstanceUrl(run, listener));
            buildRequest.setNumber(HygieiaUtils.getBuildNumber(run));
            buildRequest.setStartTime(startTime);
            buildRequest.setBuildStatus(BuildStatus.InProgress.toString());
            // need to implement clientReference at a later point during start of build
            String clientReference = null;

            buildRequest.setClientReference(clientReference);
            HygieiaResponse buildResponse = hygieiaService.publishBuildDataV3(buildRequest);
            if (buildResponse.getResponseCode() == HttpStatus.SC_CREATED) {
                try {
                    BuildDataCreateResponse buildDataResponse = HygieiaUtils.convertJsonToObject(buildResponse.getResponseValue(), BuildDataCreateResponse.class);
                    String buildString = String.format("%s,%s", buildDataResponse.getId().toString(), buildDataResponse.getCollectorItemId().toString());
                    if (showConsoleOutput) { listener.getLogger().println("Hygieia: Auto Published Build Complete Data to " + apiEndPoint + " . Response Code: " + buildResponse.getResponseCode() + ". " + buildString); }
                    publishGenericCollectorItemsOnStart(run, listener, hygieiaGlobalListenerDescriptor, hygieiaService, buildString,clientReference, buildUrl);
                } catch (IOException e) {
                    if (showConsoleOutput) { listener.getLogger().println("Hygieia: Publishing Build Complete Data to " + apiEndPoint + " , however error reading response. " + '\n' + e.getMessage()); }
                }
            } else {
                if (showConsoleOutput) { listener.getLogger().println("Hygieia: Failed Publishing Build Complete Data to " + apiEndPoint +" . " + buildResponse.toString()); }
            }
        }
    }

    @Override
    public void onFinalized(hudson.model.Run<?, ?> run) {
        super.onFinalized(run);
    }

    @Override
    public void onCompleted(Run run, @Nonnull TaskListener listener) {
        super.onCompleted(run, listener);
        HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor = getDescriptor();
        final long starttime = System.currentTimeMillis();
        boolean showConsoleOutput = hygieiaGlobalListenerDescriptor.isShowConsoleOutput();

        // if publish is not enabled and generic items collection is empty do not proceed.
        boolean publish = hygieiaGlobalListenerDescriptor.isHygieiaPublishBuildDataGlobal()
                            || hygieiaGlobalListenerDescriptor.isHygieiaPublishSonarDataGlobal()
                            || CollectionUtils.isNotEmpty(hygieiaGlobalListenerDescriptor.getHygieiaPublishGenericCollectorItems());

        if(!publish) { super.onCompleted(run, listener); return; }

        //added to print the Status of Jenkins Job before attempting to publish to Hygieia
        if(showConsoleOutput) { listener.getLogger().println("Finished: " + run.getResult()); }

        if (HygieiaUtils.isJobExcluded(run.getParent().getName(), hygieiaGlobalListenerDescriptor.getHygieiaExcludeJobNames())) {
            if (showConsoleOutput) { listener.getLogger().println("Hygieia: Skipping Automatic publish to Hygieia as the job was excluded in global configuration. "); }
            super.onCompleted(run, listener);
            return;
        }

        if (showConsoleOutput) {
            listener.getLogger().println("Hygieia: Automatically publishing build data to Hygieia using " + hygieiaGlobalListenerDescriptor.getPluginVersionInfo() + ", Please refresh your browser to see the status.");
        }

        String rawApiEndopints = StringUtils.trimToEmpty(hygieiaGlobalListenerDescriptor.getHygieiaAPIUrl());
        List<String> apiEndpints = Arrays.asList(rawApiEndopints.split(HygieiaUtils.SEPERATOR));

        int index = 0;
        if (CollectionUtils.isEmpty(apiEndpints)) {
            if (showConsoleOutput) { listener.getLogger().println("Hygieia: Skipping Automatic publish to Hygieia as no service endpoints were configured. "); }
            super.onCompleted(run, listener);
            return;
        }

        String rawAppUrls = StringUtils.trimToEmpty(hygieiaGlobalListenerDescriptor.getHygieiaAppUrl());
        List<String> appUrls = Arrays.asList(rawAppUrls.split(HygieiaUtils.SEPERATOR));

        for (String apiEndPoint : apiEndpints) {
            if (StringUtils.isEmpty(apiEndPoint)) { continue; }
            HygieiaService hygieiaService = getHygieiaService(hygieiaGlobalListenerDescriptor, apiEndPoint);
            String hygieiaAppUrl = (CollectionUtils.size(appUrls) > index) ? appUrls.get(index) : null;
            String convertedBuildResponseString = null;
            String dashboardLink = null;
            BuildDataCreateResponse buildDataCreateResponse = null;

            Triple<String, String, BuildDataCreateResponse> buildResponseTriple = publishBuildData(run, listener, hygieiaGlobalListenerDescriptor, hygieiaService, hygieiaAppUrl);

            if (buildResponseTriple != null) {
                convertedBuildResponseString = buildResponseTriple.getLeft();
                dashboardLink = buildResponseTriple.getMiddle();
                buildDataCreateResponse = buildResponseTriple.getRight();
            }
            publishSonarData(run, listener, hygieiaGlobalListenerDescriptor, hygieiaService, StringUtils.trimToNull(convertedBuildResponseString), buildDataCreateResponse);
            publishGenericCollectorItemsOnEnd(run, listener, hygieiaGlobalListenerDescriptor, hygieiaService, StringUtils.trimToNull(convertedBuildResponseString), buildDataCreateResponse);

            // publish the dashboard link
            if (showConsoleOutput && StringUtils.isNotEmpty(dashboardLink)) {
                listener.getLogger().println("Hygieia: Link to the Hygieia Dashboard for API Endpoint " + (index + 1) + " - " + dashboardLink);
            }
            index++;
        }
        final long endtime = System.currentTimeMillis();
        if (showConsoleOutput) { listener.getLogger().println("Hygieia: *** Hygieia publish completed in " + (endtime-starttime)/1000 + " seconds at " + org.joda.time.LocalDateTime.now().toString()+" ***"); }
    }

    private Triple<String, String, BuildDataCreateResponse> publishBuildData(Run run, TaskListener listener, HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor, HygieiaService hygieiaService, String hygieiaAppUrl) {
        String dashboardLink = null;
        String buildString = null;
        BuildDataCreateResponse buildDataResponse;
        boolean publishBuildData = hygieiaGlobalListenerDescriptor.isHygieiaPublishBuildDataGlobal()
                || hygieiaGlobalListenerDescriptor.isHygieiaPublishSonarDataGlobal()
                || CollectionUtils.isNotEmpty(hygieiaGlobalListenerDescriptor.getHygieiaPublishGenericCollectorItems());
        if (!publishBuildData) { return null; }

        boolean showConsoleOutput = hygieiaGlobalListenerDescriptor.isShowConsoleOutput();
        BuildStatus buildStatus = HygieiaUtils.getBuildStatus(run.getResult());
        LinkedList<BuildStage> buildStages = new LinkedList<>();
        try{
            buildStages = processStages(run, listener, hygieiaGlobalListenerDescriptor, hygieiaService);
            buildStages = process_node_links(run, listener, hygieiaGlobalListenerDescriptor, hygieiaService,buildStages);
            buildStages = process_logs(run, listener, hygieiaGlobalListenerDescriptor, hygieiaService,buildStages);
        }catch (Exception e){
            if(showConsoleOutput) { listener.getLogger().println("Hygieia: Cause for Jenkins API call failure : " + ExceptionUtils.getRootCauseMessage(e)); }
        }

        String startedBy = HygieiaUtils.getUserID(run, listener);
        if(showConsoleOutput) { listener.getLogger().println("Hygieia: This build was initiated by " + startedBy); }
        BuildDataCreateRequest buildDataCreateRequest = new BuildBuilder().createBuildRequestFromRun(run, hygieiaGlobalListenerDescriptor.getHygieiaJenkinsName(),
                listener, buildStatus, true, buildStages, startedBy);
        HygieiaResponse buildResponse = hygieiaService.publishBuildDataV3(buildDataCreateRequest);
        if (buildResponse.getResponseCode() == HttpStatus.SC_CREATED) {
            try {
                buildDataResponse = HygieiaUtils.convertJsonToObject(buildResponse.getResponseValue(), BuildDataCreateResponse.class);
                buildString = String.format("%s,%s", buildDataResponse.getId().toString(), buildDataResponse.getCollectorItemId().toString());

                if (StringUtils.isNotEmpty(hygieiaAppUrl) && buildDataResponse.getDashboardId() != null && StringUtils.isNotEmpty(buildDataResponse.getDashboardId().toString())) {
                    dashboardLink = hygieiaAppUrl + HygieiaUtils.DASHBOARD_URI + buildDataResponse.getDashboardId().toString();
                }

                if (showConsoleOutput) { listener.getLogger().println("Hygieia: Auto Published Build Complete Data. Response Code: " + buildResponse.getResponseCode() + ". " + buildString); }
            } catch (IOException e) {
                if (showConsoleOutput) { listener.getLogger().println("Hygieia: Publishing Build Complete Data, however error reading response. " + '\n' + e.getMessage()); }
                return null;
            }

        } else {
            if (showConsoleOutput) { listener.getLogger().println("Hygieia: Failed Publishing Build Complete Data. " + buildResponse.toString()); }
            return null;
        }

        return Triple.of(buildString, dashboardLink, buildDataResponse);
    }

    private LinkedList<BuildStage> processStages(Run run, TaskListener listener, HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor, HygieiaService hygieiaService) throws HygieiaException{
        LinkedList<BuildStage> buildStages=null;
        // BuildJob will not have any stages hence do not attempt restful calls to Jenkins API.
        if(run instanceof AbstractBuild) { return buildStages;}

        String buildUrl = HygieiaUtils.getBuildUrl(run);
        String wfapiUrl = buildUrl + WFAPI_DESCRIBE;

        String responseString = "";
        try{
            RestCall.RestCallResponse callResponse = hygieiaService.getStageResponse(wfapiUrl,hygieiaGlobalListenerDescriptor.getJenkinsUserId(),hygieiaGlobalListenerDescriptor.getJenkinsToken());
            if(Objects.nonNull(callResponse)){
                responseString = callResponse.getResponseString();
                buildStages=  HygieiaUtils.getBuildStages(responseString);
            }
        }catch (Exception e){

            throw new HygieiaException("HygieiaGlobalListener.processStages() - " + ExceptionUtils.getRootCauseMessage(e), e.getCause(),HygieiaException.BAD_DATA);
        }
        return buildStages;
    }

    private LinkedList<BuildStage> process_node_links(Run run, TaskListener listener, HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor, HygieiaService hygieiaService, LinkedList<BuildStage> buildStages) throws HygieiaException{
        if (CollectionUtils.isEmpty(buildStages)) return buildStages;
        for (BuildStage stage: buildStages) {
                String self_url = getSelfUrl(stage.get_links());
                String instanceUrl = HygieiaUtils.getInstanceUrl(run,listener);
                String exec_node_url = instanceUrl+self_url;
                String responseString ="";
            try{
                RestCall.RestCallResponse callResponse = hygieiaService.getStageResponse(exec_node_url,hygieiaGlobalListenerDescriptor.getJenkinsUserId(),hygieiaGlobalListenerDescriptor.getJenkinsToken());
                if(Objects.nonNull(callResponse)){
                    responseString = callResponse.getResponseString();
                    HygieiaUtils.setLogUrl(responseString,stage);
                }
            }catch (Exception e){
                throw new HygieiaException("HygieiaGlobalListener.process_node_links() - " + ExceptionUtils.getRootCauseMessage(e), e.getCause(),HygieiaException.BAD_DATA);
            }

        }
    return buildStages;
    }

    private LinkedList<BuildStage> process_logs(Run run, TaskListener listener, HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor, HygieiaService hygieiaService, LinkedList<BuildStage> buildStages) throws HygieiaException{
        if (CollectionUtils.isEmpty(buildStages)) return buildStages;
        for (BuildStage stage: buildStages) {
            boolean isCaptureLog = hygieiaGlobalListenerDescriptor.isCaptureLogs();
            if(FAILED.equalsIgnoreCase(stage.getStatus()) && isCaptureLog){
                String logUrl = stage.getExec_node_logUrl();
                String instanceUrl = HygieiaUtils.getInstanceUrl(run,listener);
                String wfapi_log_url = instanceUrl+logUrl;
                String responseString ="";
                try{
                    RestCall.RestCallResponse callResponse = hygieiaService.getStageResponse(wfapi_log_url,hygieiaGlobalListenerDescriptor.getJenkinsUserId(),hygieiaGlobalListenerDescriptor.getJenkinsToken());
                    if(Objects.nonNull(callResponse)){
                        responseString = callResponse.getResponseString();
                        HygieiaUtils.set_logs(responseString,stage);
                    }
                }catch (Exception e){
                    throw new HygieiaException("HygieiaGlobalListener.process_logs() - " + ExceptionUtils.getRootCauseMessage(e), e.getCause(),HygieiaException.BAD_DATA);
                }
            }
        }
        return buildStages;
    }

    private String getSelfUrl(Map<String,Object> _links){
       Map<String,String> href = (Map<String, String>) _links.get("self");
       String url = href.get("href");
       return url;
    }

    private void publishSonarData(Run run, TaskListener listener, HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor,
                                  HygieiaService hygieiaService, @Nonnull String convertedBuildResponseString, BuildDataCreateResponse buildDataCreateResponse) {
        if (!hygieiaGlobalListenerDescriptor.isHygieiaPublishSonarDataGlobal()) { return; }
        boolean showConsoleOutput = hygieiaGlobalListenerDescriptor.isShowConsoleOutput();
        try {
            // Quickfix by using convertedBuildResponseString to make it work with current SonarBuilder will revisit later.
            CodeQualityCreateRequest request = buildCodeQualityCreateRequest(run, listener, hygieiaGlobalListenerDescriptor.getHygieiaJenkinsName(),
                    convertedBuildResponseString, hygieiaGlobalListenerDescriptor.isUseProxy());
            if (request != null) {
                if(buildDataCreateResponse != null){
                    request.setClientReference(buildDataCreateResponse.getClientReference());
                    request.setBuildUrl(buildDataCreateResponse.getBuildUrl());
                }
                HygieiaResponse sonarResponse = hygieiaService.publishSonarResults(request);
                if (sonarResponse.getResponseCode() == HttpStatus.SC_CREATED) {
                    if (showConsoleOutput) { listener.getLogger().println("Hygieia: Auto Published Sonar Data. " + sonarResponse.toString()); }
                } else {
                    if (showConsoleOutput) { listener.getLogger().println("Hygieia: Failed Auto Publishing Sonar Data. " + sonarResponse.toString()); }
                }
            } else {
                if (showConsoleOutput) { listener.getLogger().println("Hygieia: Auto Published Sonar Result. Nothing to publish"); }
            }
        } catch (ParseException e) {
            if (showConsoleOutput) { listener.getLogger().println("Hygieia: Error Auto Publishing Sonar data." + '\n' + e.getMessage()); }
        }
    }

    private void publishGenericCollectorItemsOnEnd(Run run, TaskListener listener, HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor,
                                                   HygieiaService hygieiaService, @Nonnull String convertedBuildResponseString, BuildDataCreateResponse buildDataCreateResponse) {
        if (CollectionUtils.isEmpty(hygieiaGlobalListenerDescriptor.getHygieiaPublishGenericCollectorItems())) { return; }
        boolean showConsoleOutput = hygieiaGlobalListenerDescriptor.isShowConsoleOutput();
        List<HygieiaPublisher.GenericCollectorItem> publishItems = hygieiaGlobalListenerDescriptor.getHygieiaPublishGenericCollectorItems().stream().filter(p -> !p.isPublishOnStart()).collect(Collectors.toList());
        String clientReference = (Objects.isNull(buildDataCreateResponse)) ? null : buildDataCreateResponse.getClientReference();
        String buildUrl = (Objects.isNull(buildDataCreateResponse)) ? "" : buildDataCreateResponse.getBuildUrl();
        publishItems(run, listener, publishItems, showConsoleOutput, hygieiaService, convertedBuildResponseString, clientReference, buildUrl);
    }

    private void publishGenericCollectorItemsOnStart(Run run, TaskListener listener, HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor,
                                                     HygieiaService hygieiaService, @Nonnull String convertedBuildResponseString, String clientReference, String buildUrl) {
        if (CollectionUtils.isEmpty(hygieiaGlobalListenerDescriptor.getHygieiaPublishGenericCollectorItems())) { return; }
        boolean showConsoleOutput = hygieiaGlobalListenerDescriptor.isShowConsoleOutput();
        List<HygieiaPublisher.GenericCollectorItem> publishItems = hygieiaGlobalListenerDescriptor.getHygieiaPublishGenericCollectorItems().stream().filter(p -> p.isPublishOnStart()).collect(Collectors.toList());
        publishItems(run, listener, publishItems, showConsoleOutput, hygieiaService, convertedBuildResponseString, clientReference, buildUrl);
    }

    private void publishItems(Run run, TaskListener listener, List<HygieiaPublisher.GenericCollectorItem> items,boolean showConsoleOutput,
                              HygieiaService hygieiaService, @Nonnull String convertedBuildResponseString, String clientReference, String buildUrl) {
        if (CollectionUtils.isEmpty(items)) { return; }
        for (HygieiaPublisher.GenericCollectorItem item : items) {
            try {
                List<GenericCollectorItemCreateRequest> genericCollectorItemCreateRequests = GenericCollectorItemBuilder.getInstance().getRequests(run, item.toolName, item.pattern, convertedBuildResponseString);
                if (CollectionUtils.isEmpty(genericCollectorItemCreateRequests)) continue;
                for (GenericCollectorItemCreateRequest gcir : genericCollectorItemCreateRequests) {
                    gcir.setClientReference(clientReference);
                    gcir.setBuildUrl(buildUrl);
                    HygieiaResponse genericItemResponse = hygieiaService.publishGenericCollectorItemData(gcir);
                    if (showConsoleOutput) { listener.getLogger().println("Hygieia: Auto Published " + gcir.getToolName() + " Data. " + genericItemResponse.toString()); }
                }
            } catch (IOException e) {
                if (showConsoleOutput) { listener.getLogger().println("Hygieia: Error Auto Publishing Generic Collector Item data." + '\n' + e.getMessage()); }
            }
        }
    }

    private CodeQualityCreateRequest buildCodeQualityCreateRequest(Run run, TaskListener listener, String jenkinsName, String convertedBuildResponseString, boolean useProxy) throws ParseException {
       return SonarBuilder.getInstance().getSonarMetrics(run, listener, jenkinsName, null,
                null, convertedBuildResponseString, useProxy);
    }

    private HygieiaPublisher.DescriptorImpl getDescriptor() {
        return Objects.requireNonNull(Jenkins.getInstance()).getDescriptorByType(HygieiaPublisher.DescriptorImpl.class);
    }

    protected HygieiaService getHygieiaService(HygieiaPublisher.DescriptorImpl hygieiaGlobalListenerDescriptor, String apiEndpoint) {
        return hygieiaGlobalListenerDescriptor.getHygieiaService(apiEndpoint, hygieiaGlobalListenerDescriptor.getHygieiaToken(),
                hygieiaGlobalListenerDescriptor.getHygieiaJenkinsName(), hygieiaGlobalListenerDescriptor.isUseProxy());
    }

}
