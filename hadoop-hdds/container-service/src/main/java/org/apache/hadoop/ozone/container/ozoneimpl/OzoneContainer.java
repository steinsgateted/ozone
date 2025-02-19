/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.container.ozoneimpl;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.DatanodeDetails.Port.Name;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerType;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReplicaProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.IncrementalContainerReportProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.PipelineReportsProto;
import org.apache.hadoop.hdds.security.token.TokenVerifier;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClient;
import org.apache.hadoop.hdds.utils.HAUtils;
import org.apache.hadoop.ozone.container.common.helpers.ContainerMetrics;
import org.apache.hadoop.ozone.container.common.impl.ContainerSet;
import org.apache.hadoop.ozone.container.common.impl.HddsDispatcher;
import org.apache.hadoop.ozone.container.common.impl.StorageLocationReport;
import org.apache.hadoop.ozone.container.common.interfaces.ContainerDispatcher;
import org.apache.hadoop.ozone.container.common.interfaces.Handler;
import org.apache.hadoop.ozone.container.common.statemachine.DatanodeConfiguration;
import org.apache.hadoop.ozone.container.common.statemachine.StateContext;
import org.apache.hadoop.ozone.container.common.transport.server.XceiverServerGrpc;
import org.apache.hadoop.ozone.container.common.transport.server.XceiverServerSpi;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.XceiverServerRatis;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.apache.hadoop.ozone.container.common.volume.MutableVolumeSet;
import org.apache.hadoop.ozone.container.common.volume.StorageVolume;
import org.apache.hadoop.ozone.container.common.volume.StorageVolume.VolumeType;
import org.apache.hadoop.ozone.container.common.volume.StorageVolumeChecker;
import org.apache.hadoop.ozone.container.keyvalue.statemachine.background.BlockDeletingService;
import org.apache.hadoop.ozone.container.replication.ReplicationServer;
import org.apache.hadoop.ozone.container.replication.ReplicationServer.ReplicationConfig;
import org.apache.hadoop.util.DiskChecker.DiskOutOfSpaceException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_BLOCK_DELETING_SERVICE_TIMEOUT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_BLOCK_DELETING_SERVICE_TIMEOUT_DEFAULT;
import static org.apache.hadoop.ozone.container.ozoneimpl.ContainerScrubberConfiguration.VOLUME_BYTES_PER_SECOND_KEY;

import org.apache.hadoop.util.Timer;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ozone main class sets up the network servers and initializes the container
 * layer.
 */
public class OzoneContainer {

  private static final Logger LOG = LoggerFactory.getLogger(
      OzoneContainer.class);

  private final HddsDispatcher hddsDispatcher;
  private final Map<ContainerType, Handler> handlers;
  private final ConfigurationSource config;
  private final MutableVolumeSet volumeSet;
  private final MutableVolumeSet metaVolumeSet;
  private final StorageVolumeChecker volumeChecker;
  private final ContainerSet containerSet;
  private final XceiverServerSpi writeChannel;
  private final XceiverServerSpi readChannel;
  private final ContainerController controller;
  private ContainerMetadataScanner metadataScanner;
  private List<ContainerDataScanner> dataScanners;
  private final BlockDeletingService blockDeletingService;
  private final GrpcTlsConfig tlsClientConfig;
  private final AtomicReference<InitializingStatus> initializingStatus;
  private final ReplicationServer replicationServer;
  private DatanodeDetails datanodeDetails;
  private StateContext context;

  enum InitializingStatus {
    UNINITIALIZED, INITIALIZING, INITIALIZED
  }

  /**
   * Construct OzoneContainer object.
   *
   * @param datanodeDetails
   * @param conf
   * @param certClient
   * @throws DiskOutOfSpaceException
   * @throws IOException
   */
  public OzoneContainer(
      DatanodeDetails datanodeDetails, ConfigurationSource
      conf, StateContext context, CertificateClient certClient
  )
      throws IOException {
    config = conf;
    this.datanodeDetails = datanodeDetails;
    this.context = context;
    this.volumeChecker = getVolumeChecker(conf);

    volumeSet = new MutableVolumeSet(datanodeDetails.getUuidString(), conf,
        context, VolumeType.DATA_VOLUME, volumeChecker);
    volumeSet.setFailedVolumeListener(this::handleVolumeFailures);
    metaVolumeSet = new MutableVolumeSet(datanodeDetails.getUuidString(), conf,
        context, VolumeType.META_VOLUME, volumeChecker);

    containerSet = new ContainerSet();
    metadataScanner = null;

    buildContainerSet();
    final ContainerMetrics metrics = ContainerMetrics.create(conf);
    handlers = Maps.newHashMap();

    Consumer<ContainerReplicaProto> icrSender = containerReplicaProto -> {
      IncrementalContainerReportProto icr = IncrementalContainerReportProto
          .newBuilder()
          .addReport(containerReplicaProto)
          .build();
      context.addIncrementalReport(icr);
      context.getParent().triggerHeartbeat();
    };

    for (ContainerType containerType : ContainerType.values()) {
      handlers.put(containerType,
          Handler.getHandlerForContainerType(
              containerType, conf,
              context.getParent().getDatanodeDetails().getUuidString(),
              containerSet, volumeSet, metrics, icrSender));
    }

    SecurityConfig secConf = new SecurityConfig(conf);
    hddsDispatcher = new HddsDispatcher(config, containerSet, volumeSet,
        handlers, context, metrics, TokenVerifier.create(secConf, certClient));

    /*
     * ContainerController is the control plane
     * XceiverServerRatis is the write channel
     * XceiverServerGrpc is the read channel
     */
    controller = new ContainerController(containerSet, handlers);

    writeChannel = XceiverServerRatis.newXceiverServerRatis(
        datanodeDetails, config, hddsDispatcher, controller, certClient,
        context);

    replicationServer = new ReplicationServer(
        controller,
        conf.getObject(ReplicationConfig.class),
        secConf,
        certClient);

    readChannel = new XceiverServerGrpc(
        datanodeDetails, config, hddsDispatcher, certClient);
    Duration svcInterval = conf.getObject(
            DatanodeConfiguration.class).getBlockDeletionInterval();

    long serviceTimeout = config
        .getTimeDuration(OZONE_BLOCK_DELETING_SERVICE_TIMEOUT,
            OZONE_BLOCK_DELETING_SERVICE_TIMEOUT_DEFAULT,
            TimeUnit.MILLISECONDS);
    blockDeletingService =
        new BlockDeletingService(this, svcInterval.toMillis(), serviceTimeout,
            TimeUnit.MILLISECONDS, config);

    if (certClient != null && secConf.isGrpcTlsEnabled()) {
      List<X509Certificate> x509Certificates =
          HAUtils.buildCAX509List(certClient, conf);
      tlsClientConfig = new GrpcTlsConfig(
          certClient.getPrivateKey(), certClient.getCertificate(),
          x509Certificates, true);
    } else {
      tlsClientConfig = null;
    }

    initializingStatus =
        new AtomicReference<>(InitializingStatus.UNINITIALIZED);
  }

  public GrpcTlsConfig getTlsClientConfig() {
    return tlsClientConfig;
  }

  /**
   * Build's container map.
   */
  private void buildContainerSet() {
    Iterator<StorageVolume> volumeSetIterator = volumeSet.getVolumesList()
        .iterator();
    ArrayList<Thread> volumeThreads = new ArrayList<>();
    long startTime = System.currentTimeMillis();

    //TODO: diskchecker should be run before this, to see how disks are.
    // And also handle disk failure tolerance need to be added
    while (volumeSetIterator.hasNext()) {
      StorageVolume volume = volumeSetIterator.next();
      Thread thread = new Thread(new ContainerReader(volumeSet,
          (HddsVolume) volume, containerSet, config));
      thread.start();
      volumeThreads.add(thread);
    }

    try {
      for (int i = 0; i < volumeThreads.size(); i++) {
        volumeThreads.get(i).join();
      }
    } catch (InterruptedException ex) {
      LOG.error("Volume Threads Interrupted exception", ex);
      Thread.currentThread().interrupt();
    }

    LOG.info("Build ContainerSet costs {}s",
        (System.currentTimeMillis() - startTime) / 1000);
  }

  /**
   * Start background daemon thread for performing container integrity checks.
   */
  private void startContainerScrub() {
    ContainerScrubberConfiguration c = config.getObject(
        ContainerScrubberConfiguration.class);
    boolean enabled = c.isEnabled();

    if (!enabled) {
      LOG.info("Background container scanner has been disabled.");
    } else {
      if (this.metadataScanner == null) {
        this.metadataScanner = new ContainerMetadataScanner(c, controller);
      }
      this.metadataScanner.start();

      if (c.getBandwidthPerVolume() == 0L) {
        LOG.warn(VOLUME_BYTES_PER_SECOND_KEY + " is set to 0, " +
            "so background container data scanner will not start.");
        return;
      }

      dataScanners = new ArrayList<>();
      for (StorageVolume v : volumeSet.getVolumesList()) {
        ContainerDataScanner s = new ContainerDataScanner(c, controller,
            (HddsVolume) v);
        s.start();
        dataScanners.add(s);
      }
    }
  }

  /**
   * Stop the scanner thread and wait for thread to die.
   */
  private void stopContainerScrub() {
    if (metadataScanner == null) {
      return;
    }
    metadataScanner.shutdown();
    metadataScanner = null;

    if (dataScanners == null) {
      return;
    }
    for (ContainerDataScanner s : dataScanners) {
      s.shutdown();
    }
  }

  /**
   * Starts serving requests to ozone container.
   *
   * @throws IOException
   */
  public void start(String clusterId) throws IOException {
    // If SCM HA is enabled, OzoneContainer#start() will be called multi-times
    // from VersionEndpointTask. The first call should do the initializing job,
    // the successive calls should wait until OzoneContainer is initialized.
    if (!initializingStatus.compareAndSet(
        InitializingStatus.UNINITIALIZED, InitializingStatus.INITIALIZING)) {

      // wait OzoneContainer to finish its initializing.
      while (initializingStatus.get() != InitializingStatus.INITIALIZED) {
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      LOG.info("Ignore. OzoneContainer already started.");
      return;
    }

    LOG.info("Attempting to start container services.");
    startContainerScrub();

    replicationServer.start();
    datanodeDetails.setPort(Name.REPLICATION, replicationServer.getPort());

    writeChannel.start();
    readChannel.start();
    hddsDispatcher.init();
    hddsDispatcher.setClusterId(clusterId);
    blockDeletingService.start();

    // mark OzoneContainer as INITIALIZED.
    initializingStatus.set(InitializingStatus.INITIALIZED);
  }

  /**
   * Stop Container Service on the datanode.
   */
  public void stop() {
    //TODO: at end of container IO integration work.
    LOG.info("Attempting to stop container services.");
    stopContainerScrub();
    replicationServer.stop();
    writeChannel.stop();
    readChannel.stop();
    this.handlers.values().forEach(Handler::stop);
    hddsDispatcher.shutdown();
    volumeChecker.shutdownAndWait(0, TimeUnit.SECONDS);
    volumeSet.shutdown();
    metaVolumeSet.shutdown();
    blockDeletingService.shutdown();
    ContainerMetrics.remove();
  }

  public void handleVolumeFailures() {
    if (containerSet != null) {
      containerSet.handleVolumeFailures();
    }
  }

  @VisibleForTesting
  public ContainerSet getContainerSet() {
    return containerSet;
  }

  /**
   * Returns container report.
   *
   * @return - container report.
   */

  public PipelineReportsProto getPipelineReport() {
    PipelineReportsProto.Builder pipelineReportsProto =
        PipelineReportsProto.newBuilder();
    pipelineReportsProto.addAllPipelineReport(writeChannel.getPipelineReport());
    return pipelineReportsProto.build();
  }

  public XceiverServerSpi getWriteChannel() {
    return writeChannel;
  }

  public XceiverServerSpi getReadChannel() {
    return readChannel;
  }

  public ContainerController getController() {
    return controller;
  }

  /**
   * Returns node report of container storage usage.
   */
  public StorageContainerDatanodeProtocolProtos.NodeReportProto getNodeReport()
          throws IOException {
    StorageLocationReport[] reports = volumeSet.getStorageReport();
    StorageContainerDatanodeProtocolProtos.NodeReportProto.Builder nrb
            = StorageContainerDatanodeProtocolProtos.
            NodeReportProto.newBuilder();
    for (int i = 0; i < reports.length; i++) {
      nrb.addStorageReport(reports[i].getProtoBufMessage());
    }

    StorageLocationReport[] metaReports = metaVolumeSet.getStorageReport();
    for (int i = 0; i < metaReports.length; i++) {
      nrb.addMetadataStorageReport(
          metaReports[i].getMetadataProtoBufMessage());
    }
    return nrb.build();
  }

  @VisibleForTesting
  public ContainerDispatcher getDispatcher() {
    return this.hddsDispatcher;
  }

  public MutableVolumeSet getVolumeSet() {
    return volumeSet;
  }

  public MutableVolumeSet getMetaVolumeSet() {
    return metaVolumeSet;
  }

  @VisibleForTesting
  StorageVolumeChecker getVolumeChecker(ConfigurationSource conf) {
    return new StorageVolumeChecker(conf, new Timer());
  }

}
