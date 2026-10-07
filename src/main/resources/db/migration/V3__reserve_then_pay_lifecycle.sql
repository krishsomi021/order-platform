-- V3: lifecycle is now reserve inventory, then pay.
-- PAID and INVENTORY_PENDING are removed. ADD CONSTRAINT validates existing rows,
-- so this fails loudly if any order still has a removed status.
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status
    CHECK (status IN ('CREATED', 'PAYMENT_PENDING', 'CONFIRMED', 'CANCELLED'));