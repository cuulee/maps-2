package org.gbif.maps;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import com.google.common.base.Preconditions;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.gbif.maps.common.meta.MapMetastore;
import org.gbif.maps.common.meta.Metastores;
import org.gbif.maps.resource.*;
import org.gbif.occurrence.search.es.EsConfig;
import org.gbif.occurrence.search.heatmap.OccurrenceHeatmapService;
import org.gbif.occurrence.search.heatmap.es.EsOccurrenceHeatmapResponse;
import org.gbif.occurrence.search.heatmap.es.OccurrenceHeatmapsEsService;
import org.gbif.ws.discovery.lifecycle.DiscoveryLifeCycle;

import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

/**
 * The main entry point for running the member node.
 */
public class TileServerApplication extends Application<TileServerConfiguration> {

  private static final String APPLICATION_NAME = "GBIF Tile Server";

  public static void main(String[] args) throws Exception {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    context.reset();
    ContextInitializer initializer = new ContextInitializer(context);
    initializer.autoConfig();

    new TileServerApplication().run(args);
  }

  @Override
  public String getName() {
    return APPLICATION_NAME;
  }

  @Override
  public final void initialize(Bootstrap<TileServerConfiguration> bootstrap) {
    // We expect the assets bundle to be mounted on / in the config (applicationContextPath: "/")
    // Here we intercept the /map/debug/* URLs and serve up the content from /assets folder instead
    bootstrap.addBundle(new AssetsBundle("/assets", "/map/debug", "index.html", "assets"));
  }

  @Override
  public final void run(TileServerConfiguration configuration, Environment environment) throws Exception {
    Configuration conf = HBaseConfiguration.create();
    conf.set("hbase.zookeeper.quorum", configuration.getHbase().getZookeeperQuorum());

    EsConfig esConfig = configuration.getEsConfig().getEsConfig();
    RestClient esClient = createEsClient(esConfig);
    OccurrenceHeatmapService<EsOccurrenceHeatmapResponse> heatmapsService = new OccurrenceHeatmapsEsService(esClient, esConfig.getIndex());

    // Either use Zookeeper or static config to locate tables
    HBaseMaps hbaseMaps = null;
    if (configuration.getMetastore() != null) {
      MapMetastore meta = Metastores.newZookeeperMapsMeta(configuration.getMetastore().getZookeeperQuorum(), 1000,
        configuration.getMetastore().getPath());
      hbaseMaps = new HBaseMaps(conf, meta, configuration.getHbase().getSaltModulus());

    } else {
      //
      MapMetastore meta = Metastores.newStaticMapsMeta(configuration.getHbase().getTilesTableName(),
        configuration.getHbase().getPointsTableName());
      hbaseMaps = new HBaseMaps(conf, meta, configuration.getHbase().getSaltModulus());
    }


    TileResource tiles = new TileResource(conf,
      hbaseMaps,
      configuration.getHbase().getTileSize(),
      configuration.getHbase().getBufferSize());
    environment.jersey().register(tiles);


    // The resource that queries ES directly for HeatMap data
    environment.jersey().register(new EsResource(heatmapsService,
      configuration.getEsConfig().getTileSize(),
      configuration.getEsConfig().getBufferSize()));

    environment.jersey().register(new BackwardCompatibility(tiles));

    environment.jersey().register(NoContentResponseFilter.class);

    if (configuration.getService().isDiscoverable()) {
      environment.lifecycle().manage(new DiscoveryLifeCycle(configuration.getService()));
    }
  }

  private RestClient createEsClient(EsConfig esConfig) {
    Objects.requireNonNull(esConfig);
    Objects.requireNonNull(esConfig.getHosts());
    Preconditions.checkArgument(esConfig.getHosts().length > 0);

    HttpHost[] hosts = new HttpHost[esConfig.getHosts().length];
    int i = 0;
    for (String host : esConfig.getHosts()) {
      try {
        URL url = new URL(host);
        hosts[i] = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        i++;
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      }
    }
    return RestClient.builder(hosts).build();
  }
}
