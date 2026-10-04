CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at DESC);
DROP INDEX idx_orders_customer_id;