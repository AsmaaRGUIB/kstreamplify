package com.michelin.kafka.streams.init.initializer;

import com.michelin.kafka.streams.init.model.TopologyExposeJsonModel;
import com.michelin.kafka.streams.init.properties.KafkaProperties;
import com.michelin.kafka.streams.init.services.ConvertTopology;
import com.michelin.kafka.streams.starter.commons.context.KafkaStreamsExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.processor.internals.StreamThread;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@ConditionalOnBean(KafkaStreamsStarter.class)
public class KafkaStreamsRest {
    @Autowired
    protected ConfigurableApplicationContext applicationContext;

    @Autowired
    protected KafkaProperties kafkaProperties;

    @Autowired
    private KafkaStreamsInitializer kafkaStreamsInitializer;

    @GetMapping("ready")
    public ResponseEntity<String> readinessProbe() {
        if (kafkaStreamsInitializer.getKafkaStreams() != null) {
            log.debug("Current state of the Kafka Stream \"{}\": {}",
                    KafkaStreamsExecutionContext.getProperties().getProperty(StreamsConfig.APPLICATION_ID_CONFIG),
                    kafkaStreamsInitializer.getKafkaStreams().state());

            if (kafkaStreamsInitializer.getKafkaStreams().state() == KafkaStreams.State.REBALANCING) {
                long startingThreadCount = kafkaStreamsInitializer.getKafkaStreams().metadataForLocalThreads()
                        .stream()
                        .filter(t -> StreamThread.State.STARTING.name().compareToIgnoreCase(t.threadState()) == 0 || StreamThread.State.CREATED.name().compareToIgnoreCase(t.threadState()) == 0)
                        .count();

                if (startingThreadCount == kafkaStreamsInitializer.getKafkaStreams().metadataForLocalThreads().size()) {
                    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
                }
            }

            return kafkaStreamsInitializer.getKafkaStreams().state().isRunningOrRebalancing() ?
                    ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        return ResponseEntity.badRequest().build();
    }

    @GetMapping("liveness")
    public ResponseEntity<String> livenessProbe() {
        if (kafkaStreamsInitializer.getKafkaStreams() != null) {
            return kafkaStreamsInitializer.getKafkaStreams().state() != KafkaStreams.State.NOT_RUNNING ? ResponseEntity.ok().build()
                    : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("topology")
    public ResponseEntity<TopologyExposeJsonModel> exposeTopology() {
        if (kafkaStreamsInitializer.getTopology() != null) {
            return ResponseEntity.ok(ConvertTopology.convertTopologyForRest(kafkaProperties.getProperties().get(StreamsConfig.APPLICATION_ID_CONFIG),
                    kafkaStreamsInitializer.getTopology()));
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("dropthemic/{debounceParam}")
    public ResponseEntity<String> dropTheMic(@PathVariable() String debounceParam) {
        long debounce = 1L;

        if (!StringUtils.isBlank(debounceParam)) {
            debounce = Long.parseLong(debounceParam);
        }

        if (kafkaStreamsInitializer.getKafkaStreams() != null) {
            log.info("Clean up and close in {}s", debounce);
            kafkaStreamsInitializer.getKafkaStreams().close();
            try {
                kafkaStreamsInitializer.getKafkaStreams().cleanUp();
            } catch (IllegalStateException ex) {
                log.warn("Error on state dir clean up", ex);
            }

            long shutdownDebounce = debounce * 1000L;
            var springClose = new Thread(() -> {
                try {
                    log.info("Shutdown scheduled in {}",shutdownDebounce);
                    Thread.sleep(shutdownDebounce);
                } catch (InterruptedException e) {
                    log.warn("Error while waiting for stop", e);
                    Thread.currentThread().interrupt();
                }

                if (applicationContext != null) {
                    applicationContext.close();
                } else{
                    log.warn("No Spring context set");
                }
            });

            springClose.start();
            return new ResponseEntity<>("See you space cowboy", HttpStatus.I_AM_A_TEAPOT);
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}