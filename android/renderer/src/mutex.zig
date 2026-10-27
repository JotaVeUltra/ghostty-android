//! Plain mutex over pthreads. Zig 0.17 removed std.Thread.Mutex and std.Io.Mutex needs an Io
//! instance, which the JNI entry points do not have.

const std = @import("std");

const Mutex = @This();

inner: std.c.pthread_mutex_t = std.c.PTHREAD_MUTEX_INITIALIZER,

pub fn lock(self: *Mutex) void {
    std.debug.assert(std.c.pthread_mutex_lock(&self.inner) == .SUCCESS);
}

pub fn unlock(self: *Mutex) void {
    std.debug.assert(std.c.pthread_mutex_unlock(&self.inner) == .SUCCESS);
}
