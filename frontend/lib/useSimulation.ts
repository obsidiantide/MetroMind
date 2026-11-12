"use client";
import { Client } from "@stomp/stompjs";
import { useEffect, useState } from "react";
import { WS_URL } from "./api";
import type { Snapshot } from "./types";

/** Subscribes to live snapshots pushed by the backend over STOMP/WebSocket. */
export function useSimulation() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 2000,
      onConnect: () => {
        setConnected(true);
        client.subscribe("/topic/state", (msg) => setSnapshot(JSON.parse(msg.body)));
      },
      onWebSocketClose: () => setConnected(false),
    });
    client.activate();
    return () => {
      void client.deactivate();
    };
  }, []);

  return { snapshot, connected };
}
