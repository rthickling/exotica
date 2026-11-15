-- Run against the synthetic local database to inspect durable invariants.
select product, state, count(*) from enquiry group by product,state order by product,state;
select enquiry_id, count(*) from trade group by enquiry_id having count(*) > 1;
select idempotency_key, count(*) from trade group by idempotency_key having count(*) > 1;
select t.id,t.product,t.state,t.original_purchase_price,t.maximum_purchase_exposure,
  (select count(*) from outbox o where o.trade_id=t.id) as committed_outbox_records,
  (select count(*) from obligation o where o.trade_id=t.id) as determined_obligations
from trade t order by t.booked_at;
select state,count(*) from outbox group by state;
select trade_id,event_key,count(*) from obligation group by trade_id,event_key having count(*) > 1;
