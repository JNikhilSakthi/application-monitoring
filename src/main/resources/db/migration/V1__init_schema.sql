CREATE TABLE orders (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_name   VARCHAR(120)     NOT NULL,
    product         VARCHAR(120)     NOT NULL,
    quantity        INT              NOT NULL,
    unit_price      DECIMAL(12, 2)   NOT NULL,
    total_amount    DECIMAL(14, 2)   NOT NULL,
    status          VARCHAR(20)      NOT NULL,
    created_at      TIMESTAMP(6)     NOT NULL,
    updated_at      TIMESTAMP(6)     NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at);
