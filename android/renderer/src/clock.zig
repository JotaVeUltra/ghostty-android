//! Monotonic nanosecond clock. Zig 0.17 moved time behind std.Io, which the render loop does not have.

const std = @import("std");

pub fn nanoTimestamp() i64 {
    var ts: std.c.timespec = undefined;
    std.debug.assert(std.c.clock_gettime(.MONOTONIC, &ts) == 0);
    return @as(i64, ts.sec) * std.time.ns_per_s + ts.nsec;
}
