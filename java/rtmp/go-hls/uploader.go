package main

import (
	"context"
	"log"
	"os"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
)

const (
	uploadQueueSize = 1024
	maxUploadTries  = 3
	uploadTimeout   = 30 * time.Second
)

type uploadKind int

const (
	putSegment uploadKind = iota
	putPlaylist
	deleteObject
	deletePrefix
)

type uploadTask struct {
	kind         uploadKind
	key          string
	prefix       string
	localPath    string
	contentType  string
	cacheControl string
}

// Uploader mirrors local HLS output to S3. It is the seam used to swap in a
// fake for tests or disable uploads entirely (nil uploader = local-only mode).
type Uploader interface {
	PutSegment(key, localPath string)
	PutPlaylist(key, localPath string)
	PutPlaylistReliable(key, localPath string)
	PutThumbnail(key, localPath string)
	Delete(key string)
	DeleteAll(prefix string)
}

// s3Uploader asynchronously uploads/prunes objects on a single worker goroutine.
// Uploads never run on the segmenting path; a bounded queue keeps ingest from
// blocking on S3 latency.
type s3Uploader struct {
	client *s3.Client
	bucket string
	queue  chan uploadTask
	done   chan struct{}
}

func newS3Uploader(ctx context.Context, bucket, region string) (*s3Uploader, error) {
	opts := []func(*config.LoadOptions) error{}
	if region != "" {
		opts = append(opts, config.WithRegion(region))
	}
	cfg, err := config.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return nil, err
	}
	return &s3Uploader{
		client: s3.NewFromConfig(cfg),
		bucket: bucket,
		queue:  make(chan uploadTask, uploadQueueSize),
		done:   make(chan struct{}),
	}, nil
}

func (u *s3Uploader) Start() {
	go u.loop()
}

// Close stops the worker and blocks until queued tasks are drained (or ctx
// expires). Must be called only after the segmenter has finished enqueuing.
func (u *s3Uploader) Close(ctx context.Context) {
	close(u.queue)
	select {
	case <-u.done:
	case <-ctx.Done():
	}
}

func (u *s3Uploader) PutSegment(key, localPath string) {
	u.enqueue(uploadTask{
		kind:         putSegment,
		key:          key,
		localPath:    localPath,
		contentType:  "video/mp4",
		cacheControl: "public,max-age=10",
	})
}

func (u *s3Uploader) PutPlaylist(key, localPath string) {
	// Playlists are rewritten every second and MUST land: a dropped (stale)
	// playlist stalls viewers until the cache TTL passes. The worker drains the
	// bounded queue quickly, so blocking enqueue never stalls ingest in practice.
	u.enqueue(uploadTask{
		kind:         putPlaylist,
		key:          key,
		localPath:    localPath,
		contentType:  "application/vnd.apple.mpegurl",
		cacheControl: "no-cache,no-store,must-revalidate",
	})
}

// PutPlaylistReliable is kept for one-shot files like master.m3u8 where a
// dropped task would not be superseded by a later emit. PutPlaylist is now
// reliable (blocking) as well, so this is an alias.
func (u *s3Uploader) PutPlaylistReliable(key, localPath string) {
	u.PutPlaylist(key, localPath)
}

func (u *s3Uploader) PutThumbnail(key, localPath string) {
	u.enqueue(uploadTask{
		kind:         putSegment,
		key:          key,
		localPath:    localPath,
		contentType:  "image/jpeg",
		cacheControl: "public,max-age=30",
	})
}

func (u *s3Uploader) Delete(key string) {
	u.enqueue(uploadTask{kind: deleteObject, key: key})
}

func (u *s3Uploader) DeleteAll(prefix string) {
	u.enqueue(uploadTask{kind: deletePrefix, prefix: prefix})
}

func (u *s3Uploader) enqueue(task uploadTask) {
	u.queue <- task
}

func (u *s3Uploader) loop() {
	defer close(u.done)
	for task := range u.queue {
		u.process(task)
	}
}

func (u *s3Uploader) process(task uploadTask) {
	var err error
	for attempt := 1; attempt <= maxUploadTries; attempt++ {
		switch task.kind {
		case putSegment, putPlaylist:
			err = u.put(task)
		case deleteObject:
			err = u.delete(task)
		case deletePrefix:
			err = u.deletePrefix(task.prefix)
		}
		if err == nil {
			return
		}
		log.Printf("s3 upload failed (attempt %d/%d) task=%+v: %v", attempt, maxUploadTries, task, err)
		time.Sleep(time.Duration(attempt) * 500 * time.Millisecond)
	}
}

func (u *s3Uploader) put(task uploadTask) error {
	ctx, cancel := context.WithTimeout(context.Background(), uploadTimeout)
	defer cancel()

	f, err := os.Open(task.localPath)
	if err != nil {
		return err
	}
	defer f.Close()

	_, err = u.client.PutObject(ctx, &s3.PutObjectInput{
		Bucket:       aws.String(u.bucket),
		Key:          aws.String(task.key),
		Body:         f,
		ContentType:  aws.String(task.contentType),
		CacheControl: aws.String(task.cacheControl),
	})
	return err
}

func (u *s3Uploader) delete(task uploadTask) error {
	ctx, cancel := context.WithTimeout(context.Background(), uploadTimeout)
	defer cancel()

	_, err := u.client.DeleteObject(ctx, &s3.DeleteObjectInput{
		Bucket: aws.String(u.bucket),
		Key:    aws.String(task.key),
	})
	return err
}

func (u *s3Uploader) deletePrefix(prefix string) error {
	ctx, cancel := context.WithTimeout(context.Background(), uploadTimeout)
	defer cancel()

	paginator := s3.NewListObjectsV2Paginator(u.client, &s3.ListObjectsV2Input{
		Bucket: aws.String(u.bucket),
		Prefix: aws.String(prefix),
	})

	var keys []types.ObjectIdentifier
	flush := func() error {
		if len(keys) == 0 {
			return nil
		}
		_, err := u.client.DeleteObjects(ctx, &s3.DeleteObjectsInput{
			Bucket: aws.String(u.bucket),
			Delete: &types.Delete{Objects: keys, Quiet: aws.Bool(true)},
		})
		keys = nil
		return err
	}

	for paginator.HasMorePages() {
		page, err := paginator.NextPage(ctx)
		if err != nil {
			return err
		}
		for _, obj := range page.Contents {
			keys = append(keys, types.ObjectIdentifier{Key: obj.Key})
			if len(keys) >= 1000 {
				if err := flush(); err != nil {
					return err
				}
			}
		}
	}
	return flush()
}
