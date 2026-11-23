use crate::sqlite_store::{PeerKey, PushRegistrationRecord, SqliteStore};

async fn test_store() -> (SqliteStore, std::path::PathBuf, String) {
    let path = std::env::temp_dir().join(format!("kompi-push-storage-{}", uuid::Uuid::new_v4()));
    let store = SqliteStore::open(&path).await.unwrap();
    let peer = "a".repeat(64);
    store.ensure_peer(&PeerKey::try_from(peer.clone()).unwrap(), "test", 1).await.unwrap();
    (store, path, peer)
}

async fn register(store: &SqliteStore, peer: &str, registration_id: &str) {
    store.replace_push_registration(&PushRegistrationRecord {
        peer: peer.to_owned(),
        registration_id: registration_id.to_owned(),
        origin: "https://push.example".to_owned(),
        p256dh: "public-key-only".to_owned(),
        encrypted_secrets: vec![0xA5; 64],
    }).await.unwrap();
}

async fn cleanup(store: SqliteStore, path: std::path::PathBuf) {
    drop(store);
    let _ = tokio::fs::remove_dir_all(path).await;
}

#[tokio::test]
async fn direct_unknown_and_push_acceptance_share_durable_receipts_across_restart() {
    let (store, path, peer) = test_store().await;
    let registration_id = uuid::Uuid::new_v4().to_string();
    register(&store, &peer, &registration_id).await;
    let fingerprint = [7u8; 32];
    assert_eq!(store.reserve_direct_notification(&peer, "notice-1", &fingerprint, 1_000).await.unwrap(), (true, "direct-dispatching".into(), None));
    drop(store);

    let store = SqliteStore::open(&path).await.unwrap();
    store.recover_notification_receipts(2_000).await.unwrap();
    let recovered = store.reserve_direct_notification(&peer, "notice-1", &fingerprint, 2_001).await.unwrap();
    assert_eq!(recovered, (false, "unknown".into(), Some("daemon-restarted-after-dispatch".into())));
    let retry_via_push = store.enqueue_push(&peer, "notice-1", &registration_id, &[0xA5; 24], &fingerprint, 80_000_000, 2_002).await.unwrap();
    assert_eq!(retry_via_push.0, "unknown");
    assert!(store.due_pushes(2_003, 10).await.unwrap().is_empty());

    let fingerprint2 = [8u8; 32];
    assert_eq!(store.enqueue_push(&peer, "notice-2", &registration_id, &[0x5A; 24], &fingerprint2, 80_000_000, 2_004).await.unwrap().0, "queued");
    assert_eq!(store.reserve_direct_notification(&peer, "notice-2", &fingerprint2, 2_005).await.unwrap(), (false, "queued".into(), None));
    store.update_push(&peer, "notice-2", "push-accepted", None, 1, 80_000_000, true).await.unwrap();
    assert_eq!(store.reserve_direct_notification(&peer, "notice-2", &fingerprint2, 2_006).await.unwrap(), (false, "push-accepted".into(), None));
    cleanup(store, path).await;
}

#[tokio::test]
async fn expired_queue_is_terminalized_before_cleanup_and_same_id_cannot_be_requeued() {
    let (store, path, peer) = test_store().await;
    let registration_id = uuid::Uuid::new_v4().to_string();
    register(&store, &peer, &registration_id).await;
    let fingerprint = [9u8; 32];
    assert_eq!(store.enqueue_push(&peer, "notice-expire", &registration_id, &[0xCC; 32], &fingerprint, 5_000, 1_000).await.unwrap().0, "queued");
    store.expire_pushes(5_001).await.unwrap();
    let retry = store.enqueue_push(&peer, "notice-expire", &registration_id, &[0xDD; 32], &fingerprint, 50_000, 5_002).await.unwrap();
    assert_eq!(retry.0, "not-posted");
    assert_eq!(retry.1.as_deref(), Some("expired"));
    assert!(store.due_pushes(5_003, 10).await.unwrap().is_empty());
    cleanup(store, path).await;
}

#[tokio::test]
async fn rotation_cancels_old_outbox_and_stale_unregister_cannot_remove_current_binding() {
    let (store, path, peer) = test_store().await;
    let old_id = uuid::Uuid::new_v4().to_string();
    let new_id = uuid::Uuid::new_v4().to_string();
    register(&store, &peer, &old_id).await;
    let fingerprint = [10u8; 32];
    store.enqueue_push(&peer, "notice-rotate", &old_id, &[0xEE; 32], &fingerprint, 90_000, 1_000).await.unwrap();
    register(&store, &peer, &new_id).await;
    assert!(!store.remove_push_registration(&peer, &old_id).await.unwrap());
    assert_eq!(store.push_registration(&peer).await.unwrap().unwrap().registration_id, new_id);
    let receipt = sqlx::query("SELECT status,code FROM push_outbox_history WHERE peer=? AND request_id='notice-rotate'")
        .bind(&peer).fetch_one(&store.pool).await.unwrap();
    use sqlx::Row;
    assert_eq!(receipt.get::<String,_>("status"), "not-posted");
    assert_eq!(receipt.get::<Option<String>,_>("code").as_deref(), Some("registration-rotated"));
    assert!(store.due_pushes(1_001, 10).await.unwrap().is_empty());
    cleanup(store, path).await;
}

#[tokio::test]
async fn exact_retry_returns_prior_receipt_even_when_queue_is_full() {
    let (store, path, peer) = test_store().await;
    let registration_id = uuid::Uuid::new_v4().to_string();
    register(&store, &peer, &registration_id).await;
    let fingerprint = [21u8; 32];
    for index in 0..128 {
        store.enqueue_push(&peer, &format!("notice-capacity-{index}"), &registration_id, &[index as u8; 24], &fingerprint, 90_000, 1_000).await.unwrap();
    }
    let exact = store.enqueue_push(&peer, "notice-capacity-0", &registration_id, &[0; 24], &fingerprint, 90_000, 1_001).await.unwrap();
    assert_eq!(exact, ("queued".into(), None, true));
    let conflict = store.enqueue_push(&peer, "notice-capacity-0", &registration_id, &[0; 24], &[22; 32], 90_000, 1_001).await.unwrap();
    assert_eq!(conflict, ("not-posted".into(), Some("request-id-conflict".into()), false));
    assert!(store.enqueue_push(&peer, "notice-capacity-new", &registration_id, &[1; 24], &[23; 32], 90_000, 1_001).await.is_err());
    cleanup(store, path).await;
}

#[tokio::test]
async fn retry_reuses_the_same_ciphertext_until_expiry_then_acceptance_clears_it() {
    let (store, path, peer) = test_store().await;
    let registration_id = uuid::Uuid::new_v4().to_string();
    register(&store, &peer, &registration_id).await;
    let fingerprint = [12u8; 32];
    let ciphertext = [0xB7; 24];
    store.enqueue_push(&peer, "notice-retry", &registration_id, &ciphertext, &fingerprint, 90_000, 1_000).await.unwrap();
    store.update_push(&peer, "notice-retry", "retry", Some("provider-temporary-failure"), 1, 5_000, false).await.unwrap();
    assert!(store.due_pushes(4_999, 10).await.unwrap().is_empty());
    assert_eq!(store.due_pushes(5_000, 10).await.unwrap()[0].3, ciphertext);
    store.update_push(&peer, "notice-retry", "push-accepted", None, 2, 90_000, true).await.unwrap();
    assert!(store.due_pushes(5_001, 10).await.unwrap().is_empty());
    let stored: Vec<u8> = sqlx::query_scalar("SELECT ciphertext FROM push_outbox WHERE peer=? AND request_id='notice-retry'")
        .bind(&peer).fetch_one(&store.pool).await.unwrap();
    assert!(stored.is_empty());
    cleanup(store, path).await;
}

#[tokio::test]
async fn device_revoke_removes_registration_and_cancels_queued_push() {
    let (store, path, peer) = test_store().await;
    let registration_id = uuid::Uuid::new_v4().to_string();
    register(&store, &peer, &registration_id).await;
    let fingerprint = [13u8; 32];
    store.enqueue_push(&peer, "notice-revoke", &registration_id, &[0xCA; 24], &fingerprint, 90_000, 1_000).await.unwrap();
    assert!(store.revoke_device(&peer).await.unwrap());
    assert!(store.push_registration(&peer).await.unwrap().is_none());
    assert!(store.due_pushes(1_001, 10).await.unwrap().is_empty());
    let stale = store.enqueue_push(&peer, "notice-revoke", &registration_id, &[0xCA; 24], &fingerprint, 90_000, 1_002).await.unwrap();
    assert_eq!(stale.0, "unavailable");
    assert_eq!(stale.1.as_deref(), Some("no-background-route"));
    cleanup(store, path).await;
}

#[tokio::test]
async fn notification_storage_has_ciphertext_only_and_no_content_columns() {
    let (store, path, peer) = test_store().await;
    let registration_id = uuid::Uuid::new_v4().to_string();
    register(&store, &peer, &registration_id).await;
    let encrypted = b"opaque-ciphertext";
    let fingerprint = [11u8; 32];
    store.enqueue_push(&peer, "notice-private", &registration_id, encrypted, &fingerprint, 90_000, 1_000).await.unwrap();
    let stored: Vec<u8> = sqlx::query_scalar("SELECT ciphertext FROM push_outbox WHERE peer=? AND request_id='notice-private'")
        .bind(&peer).fetch_one(&store.pool).await.unwrap();
    assert_eq!(stored, encrypted);
    let columns: Vec<String> = sqlx::query_scalar("SELECT name FROM pragma_table_info('push_outbox')")
        .fetch_all(&store.pool).await.unwrap();
    assert!(!columns.iter().any(|name| name.contains("title") || name.contains("body")));
    cleanup(store, path).await;
}
