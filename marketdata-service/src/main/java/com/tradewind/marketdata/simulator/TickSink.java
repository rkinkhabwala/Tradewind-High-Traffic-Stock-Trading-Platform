package com.tradewind.marketdata.simulator;

import java.util.List;

/** Receives each batch of ticks the simulator produced in one loop iteration. */
public interface TickSink {

    void accept(List<SimulatedTick> batch);
}
