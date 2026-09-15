package main

import "testing"

// TestNilUploaderEnqueueIsNoOp guards against the production panic where a
// typed-nil *s3Uploader (nil pointer stored in the Uploader interface) reached
// enqueue and sent on its nil queue. A nil receiver must be a no-op so the
// "nil uploader = local-only mode" contract can never crash the daemon.
func TestNilUploaderEnqueueIsNoOp(t *testing.T) {
	var u *s3Uploader
	u.enqueue(uploadTask{
		kind:        putSegment,
		key:         "hls/x/hd/output_1.m4s",
		localPath:   "unused",
		contentType: "video/mp4",
	})
}
