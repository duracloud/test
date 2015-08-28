package org.duraspace.tools.storage;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Module;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.openstack.swift.SwiftApiMetadata;
import org.jclouds.openstack.swift.SwiftClient;
import org.jclouds.openstack.swift.domain.ObjectInfo;
import org.jclouds.openstack.swift.options.ListContainerOptions;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.Properties;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/*
 * Container Lister Tool - Provides a way to get a listing of
 *   all items in an OpenStack swift container using JClouds
 *   to talk directly to SDSC
 *
 * @author: Bill Branan
 * Date: Aug 28, 2015
 */
public class ContainerListerTool {

    private static final String authUrl =
        "https://duracloud.auth.cloud.sdsc.edu/auth/v1.0";

    private String spaceName;
    private String username;
    private String password;
    private int maxSize;
    private String outputFilePath;

    private static Options cmdOptions;

    public ContainerListerTool(String spaceName,
                               String username,
                               String password,
                               int maxSize,
                               String outputFile) {
        this.spaceName = spaceName;
        this.username = username;
        this.password = password;
        this.maxSize = maxSize;
        this.outputFilePath = outputFile;
    }

    public void run() throws Exception {
        System.out.println("Running Space Lister Tool (JClouds) with config:" +
                           "\nspace name=" + spaceName +
                           "\nuser name=" + username +
                           "\nmax size=" + maxSize);

        System.out.println("Setting up tool...");

        String trimmedAuthUrl = // JClouds expects authURL with no version
            authUrl.substring(0, authUrl.lastIndexOf("/"));

        ListeningExecutorService useExecutor = createThreadPool();
        ListeningExecutorService ioExecutor = createThreadPool();

        Iterable<Module> modules = ImmutableSet.<Module> of(
            new EnterpriseConfigurationModule(useExecutor, ioExecutor));
        Properties properties = new Properties();
        properties.setProperty(Constants.PROPERTY_STRIP_EXPECT_HEADER,
                               "true");
        SwiftClient swiftClient = ContextBuilder.newBuilder(new SwiftApiMetadata())
                                    .endpoint(trimmedAuthUrl)
                                    .credentials(username, password)
                                    .modules(modules)
                                    .overrides(properties)
                                    .buildApi(SwiftClient.class);

        doList(swiftClient, spaceName);

        System.out.println("Space Lister Tool process complete.");
    }

    protected ListeningExecutorService createThreadPool() {
        return MoreExecutors.listeningDecorator(
            new ThreadPoolExecutor(0,
                                   Integer.MAX_VALUE,
                                   5L,
                                   TimeUnit.SECONDS,
                                   new SynchronousQueue<Runnable>()));
    }

    private void doList(SwiftClient swiftClient, String spaceId)
        throws Exception {

        File outputFile = new File(spaceId + "-content-listing-sdsc-jclouds.txt");

        if(outputFilePath != null){
            outputFile = new File(outputFilePath);
            outputFile.getParentFile().mkdirs();
        }
        
        System.out.println("Writing space listing to: " + outputFile.getAbsolutePath());
        Writer writer = new FileWriter(outputFile);
        
        String marker = null;
        PageSet<ObjectInfo> objects = listObjects(swiftClient, spaceName, maxSize, marker);
        int itemsInList = objects.size();
        System.out.println("Items in returned set: " + objects.size());
        while(objects.size() > 0) {
            for (ObjectInfo object : objects) {
                marker = object.getName();
                writer.write(marker + "\n");
            }
            objects = listObjects(swiftClient, spaceName, maxSize, marker);
            itemsInList += objects.size();
            System.out.println("Items in returned set: " + objects.size() +
                               ". Total: " + itemsInList);
        }

        writer.flush();
        writer.close();
    }

    private PageSet<ObjectInfo> listObjects(SwiftClient swiftClient,
                                            String containerName,
                                            int limit,
                                            String marker) {
        ListContainerOptions containerOptions =
            ListContainerOptions.Builder.maxResults(limit);
        if(marker != null) containerOptions.afterMarker(marker);
        return swiftClient.listObjects(containerName, containerOptions);
    }

    public static void main(String[] args) throws Exception {
        cmdOptions = new Options();

        Option spaceNameOption =
            new Option("s", "spacename", true, "the space name to list");
        spaceNameOption.setRequired(true);
        cmdOptions.addOption(spaceNameOption);

        Option usernameOption =
           new Option("u", "username", true,
                      "the username necessary to perform writes to DuraStore");
        usernameOption.setRequired(true);
        cmdOptions.addOption(usernameOption);

        Option passwordOption =
           new Option("p", "password", true,
                      "the password necessary to perform writes to DuraStore");
        passwordOption.setRequired(true);
        cmdOptions.addOption(passwordOption);

        Option maxSizeOption =
            new Option("m", "maxsize", true,
                       "the max size of request results from open stack");
        maxSizeOption.setRequired(true);
        cmdOptions.addOption(maxSizeOption);

        Option outputFileOption =
                new Option("o", "output-file", true,
                           "The file to output results to");
             outputFileOption.setRequired(false);
             cmdOptions.addOption(outputFileOption);

        CommandLine cmd = null;
        try {
            CommandLineParser parser = new PosixParser();
            cmd = parser.parse(cmdOptions, args);
        } catch(ParseException e) {
            System.out.println(e.getMessage());
            usage();
        }

        String spacename = cmd.getOptionValue("s");
        String username = cmd.getOptionValue("u");
        String password = cmd.getOptionValue("p");
        int maxSize = Integer.parseInt(cmd.getOptionValue("m"));
        String outputFile = cmd.getOptionValue("o");

        ContainerListerTool tool =
            new ContainerListerTool(spacename, username, password, maxSize, outputFile);
        tool.run();
    }

    private static void usage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("Running the Space Lister Tool (JClouds)", cmdOptions);
        System.exit(1);
    }

}