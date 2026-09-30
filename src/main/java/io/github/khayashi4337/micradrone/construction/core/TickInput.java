package io.github.khayashi4337.micradrone.construction.core;

/** One server tick as the job service sees it: the tick number and the average tick time the budget slows down on. */
public record TickInput(long tick, double averageMspt) {
}
