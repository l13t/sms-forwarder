#!/usr/bin/env python3
"""Exercise actual DAO SQL in SQLite; does not replace Room instrumentation."""
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
source=(ROOT/'app/src/main/java/ua/smsforwarder/data/Database.kt').read_text()
queries={name: sql[3:-3] if sql.startswith('"""') else sql[1:-1]
         for sql,name in re.findall(r'@Query\(("""[\s\S]*?"""|"[^"\n]*")\)\s*(?:suspend\s+)?fun\s+(\w+)',source)}

class QueueSqlTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='sms-forwarder-sql-')
        self.path=str(Path(self.temp.name)/'queue.db')
        self.db=sqlite3.connect(self.path)
        self.db.row_factory=sqlite3.Row
        # DDL follows the v1 entities; all behavioral SQL below comes from the DAO.
        self.db.executescript('''
            CREATE TABLE webhooks(id TEXT PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, encryptedHeaders TEXT NOT NULL, enabled INTEGER NOT NULL);
            CREATE TABLE events(id TEXT PRIMARY KEY, sourceKey TEXT NOT NULL UNIQUE, createdAt INTEGER NOT NULL);
            CREATE TABLE deliveries(id TEXT PRIMARY KEY, eventId TEXT NOT NULL, webhookId TEXT NOT NULL, payload TEXT NOT NULL, url TEXT NOT NULL, encryptedHeaders TEXT NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, retryStartedAt INTEGER NOT NULL, nextAt INTEGER NOT NULL, leaseUntil INTEGER NOT NULL, lastError TEXT, completedAt INTEGER, UNIQUE(eventId,webhookId));
            CREATE TABLE cursors(`key` TEXT PRIMARY KEY, value TEXT NOT NULL, updatedAt INTEGER NOT NULL);
            INSERT INTO webhooks VALUES('a','A','https://example.com','encrypted',1);
            INSERT INTO webhooks VALUES('b','B','https://example.com','encrypted',0);
        ''')
    def tearDown(self):
        self.db.close(); self.temp.cleanup()
    def query(self,name,**args):
        return self.db.execute(queries[name],args)
    def add(self,id='d1',hook='a',event='e1',created=100,status='pending',next_at=0,lease=0,completed=None):
        self.db.execute('INSERT INTO deliveries VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)',(id,event,hook,'{}','https://example.com','encrypted',created,status,0,created,next_at,lease,None,completed))
    def test_claim_prevents_parallel_send_and_recovers_expired_lease(self):
        self.add()
        self.assertEqual('d1',self.query('next',now=200).fetchone()['id'])
        self.assertEqual(1,self.query('claim',id='d1',now=200,lease=120200).rowcount)
        self.assertEqual(0,self.query('claim',id='d1',now=200,lease=120200).rowcount)
        self.assertIsNone(self.query('next',now=201).fetchone())
        self.assertEqual('d1',self.query('next',now=120201).fetchone()['id'])
    def test_backoff_and_disabled_webhook_are_respected(self):
        self.add(next_at=1000)
        self.add('d2','b','e2')
        self.assertIsNone(self.query('next',now=999).fetchone())
        self.assertEqual('d1',self.query('next',now=1000).fetchone()['id'])
    def test_pending_delivery_survives_reopening_database(self):
        self.add(); self.db.commit(); self.db.close()
        self.db=sqlite3.connect(self.path); self.db.row_factory=sqlite3.Row
        self.assertEqual('d1',self.query('next',now=200).fetchone()['id'])
    def test_manual_retry_resets_retry_window_and_keeps_payload_and_id(self):
        self.add(status='failed',completed=200)
        self.query('retry',id='d1',now=900000000)
        row=self.query('delivery',id='d1').fetchone()
        self.assertEqual(('d1','{}','pending',900000000,None),(row['id'],row['payload'],row['status'],row['retryStartedAt'],row['completedAt']))
    def test_webhook_deletion_cancels_unfinished_only(self):
        self.add(); self.add('d2','a','e2',status='sent',completed=200)
        self.query('cancelWebhook',id='a'); self.query('deleteWebhook',id='a')
        self.assertIsNone(self.query('delivery',id='d1').fetchone())
        self.assertEqual('sent',self.query('delivery',id='d2').fetchone()['status'])
    def test_retention_preserves_recent_pending_and_removes_orphan_events(self):
        self.add('old','a','e1',created=1,status='sent',completed=2)
        self.add('pending','a','e2',created=500)
        self.db.executemany('INSERT INTO events VALUES(?,?,?)',[('e1','one',1),('e2','two',1),('e3','three',1)])
        self.query('pruneDeliveries',finishedBefore=100,allBefore=10)
        self.query('pruneEvents',before=100)
        self.assertEqual(['e2'],[r[0] for r in self.db.execute('SELECT id FROM events')])
        self.assertEqual('pending',self.query('delivery',id='pending').fetchone()['status'])
    def test_destination_pair_and_source_key_are_unique(self):
        self.add()
        with self.assertRaises(sqlite3.IntegrityError): self.add('duplicate')
        self.add('different-target','b')
        self.db.execute("INSERT INTO events VALUES('e1','source',100)")
        self.assertEqual(0,self.db.execute("INSERT OR IGNORE INTO events VALUES('e2','source',100)").rowcount)
    def test_clearing_history_keeps_pending_and_claimed_deliveries(self):
        self.add(); self.add('sending','a','e2',status='sending',lease=900)
        self.add('sent','a','e3',status='sent',completed=200)
        self.query('clearFinished')
        self.assertEqual(2,self.db.execute('SELECT count(*) FROM deliveries').fetchone()[0])

if __name__=='__main__': unittest.main(verbosity=2)
