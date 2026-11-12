package com.metromind.infra;

import com.metromind.sim.SimulationSnapshot;
import com.metromind.sim.SnapshotListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Pushes every snapshot to STOMP subscribers of {@code /topic/state}. */
@Component
public class WebSocketSnapshotPublisher implements SnapshotListener {

    private final SimpMessagingTemplate template;

    public WebSocketSnapshotPublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void onSnapshot(SimulationSnapshot snapshot) {
        template.convertAndSend("/topic/state", snapshot);
    }
}
