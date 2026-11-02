--
-- PostgreSQL database dump
--

\restrict YWEavEe7GBpbhzU6wHypEIsDbUXi3SJZDQiHPPomgg4wqT1nSuvROEUIdY5NHkl

-- Dumped from database version 18.4
-- Dumped by pg_dump version 18.4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: admin_accounts; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.admin_accounts (
    id bigint NOT NULL,
    username character varying(100) NOT NULL,
    password_hash character varying(255) NOT NULL,
    role character varying(20) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone NOT NULL
);


ALTER TABLE public.admin_accounts OWNER TO postgres;

--
-- Name: admin_accounts_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.admin_accounts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.admin_accounts_id_seq OWNER TO postgres;

--
-- Name: admin_accounts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.admin_accounts_id_seq OWNED BY public.admin_accounts.id;


--
-- Name: chat_conversation_participants; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.chat_conversation_participants (
    id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    joined_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_read_message_id bigint
);


ALTER TABLE public.chat_conversation_participants OWNER TO postgres;

--
-- Name: chat_conversation_participants_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.chat_conversation_participants_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.chat_conversation_participants_id_seq OWNER TO postgres;

--
-- Name: chat_conversation_participants_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.chat_conversation_participants_id_seq OWNED BY public.chat_conversation_participants.id;


--
-- Name: chat_conversations; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.chat_conversations (
    id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.chat_conversations OWNER TO postgres;

--
-- Name: chat_conversations_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.chat_conversations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.chat_conversations_id_seq OWNER TO postgres;

--
-- Name: chat_conversations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.chat_conversations_id_seq OWNED BY public.chat_conversations.id;


--
-- Name: chat_inspection_audit; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.chat_inspection_audit (
    id bigint NOT NULL,
    admin_account_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    reason character varying(500) NOT NULL,
    action character varying(30) DEFAULT 'VIEW'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_chat_inspection_action CHECK (((action)::text = ANY ((ARRAY['VIEW'::character varying, 'EXPORT'::character varying])::text[])))
);


ALTER TABLE public.chat_inspection_audit OWNER TO postgres;

--
-- Name: chat_inspection_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.chat_inspection_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.chat_inspection_audit_id_seq OWNER TO postgres;

--
-- Name: chat_inspection_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.chat_inspection_audit_id_seq OWNED BY public.chat_inspection_audit.id;


--
-- Name: chat_message_receipts; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.chat_message_receipts (
    id bigint NOT NULL,
    message_id bigint NOT NULL,
    user_id bigint NOT NULL,
    delivered_at timestamp with time zone,
    read_at timestamp with time zone
);


ALTER TABLE public.chat_message_receipts OWNER TO postgres;

--
-- Name: chat_message_receipts_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.chat_message_receipts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.chat_message_receipts_id_seq OWNER TO postgres;

--
-- Name: chat_message_receipts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.chat_message_receipts_id_seq OWNED BY public.chat_message_receipts.id;


--
-- Name: chat_messages; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.chat_messages (
    id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    sender_user_id bigint NOT NULL,
    ciphertext text,
    nonce character varying(32),
    encryption_key_version integer NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    edited_at timestamp with time zone,
    deleted_at timestamp with time zone,
    message_type character varying(10) DEFAULT 'TEXT'::character varying NOT NULL,
    media_storage_key character varying(500),
    media_content_type character varying(100),
    media_size_bytes bigint,
    CONSTRAINT chk_chat_message_ciphertext CHECK ((length(ciphertext) > 0)),
    CONSTRAINT chk_chat_message_key_version CHECK ((encryption_key_version > 0)),
    CONSTRAINT chk_chat_message_nonce CHECK ((length((nonce)::text) > 0)),
    CONSTRAINT chk_chat_messages_content_by_type CHECK (((((message_type)::text = 'TEXT'::text) AND (length(ciphertext) > 0)) OR (((message_type)::text = 'IMAGE'::text) AND (length(ciphertext) > 0)))),
    CONSTRAINT chk_chat_messages_image_metadata CHECK (((((message_type)::text = 'TEXT'::text) AND (media_storage_key IS NULL) AND (media_content_type IS NULL) AND (media_size_bytes IS NULL)) OR (((message_type)::text = 'IMAGE'::text) AND (media_storage_key IS NOT NULL) AND ((media_content_type)::text = ANY (ARRAY[('image/jpeg'::character varying)::text, ('image/png'::character varying)::text])) AND (media_size_bytes IS NOT NULL) AND (media_size_bytes > 0)))),
    CONSTRAINT chk_chat_messages_media_size CHECK (((media_size_bytes IS NULL) OR (media_size_bytes > 0))),
    CONSTRAINT chk_chat_messages_message_type CHECK (((message_type)::text = ANY (ARRAY[('TEXT'::character varying)::text, ('IMAGE'::character varying)::text])))
);


ALTER TABLE public.chat_messages OWNER TO postgres;

--
-- Name: chat_messages_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.chat_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.chat_messages_id_seq OWNER TO postgres;

--
-- Name: chat_messages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.chat_messages_id_seq OWNED BY public.chat_messages.id;


--
-- Name: coin_packs; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.coin_packs (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    coins bigint NOT NULL,
    price_in_rupees numeric(10,2) NOT NULL,
    bonus_coins bigint DEFAULT 0 NOT NULL,
    bonus_percentage integer DEFAULT 0 NOT NULL,
    badge character varying(30),
    image_url character varying(1000),
    image_public_id character varying(255),
    active boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT coin_packs_bonus_coins_check CHECK ((bonus_coins >= 0)),
    CONSTRAINT coin_packs_bonus_percentage_check CHECK (((bonus_percentage >= 0) AND (bonus_percentage <= 100))),
    CONSTRAINT coin_packs_coins_check CHECK ((coins > 0)),
    CONSTRAINT coin_packs_price_in_rupees_check CHECK ((price_in_rupees > (0)::numeric)),
    CONSTRAINT coin_packs_sort_order_check CHECK ((sort_order >= 0))
);


ALTER TABLE public.coin_packs OWNER TO postgres;

--
-- Name: coin_packs_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.coin_packs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.coin_packs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: coin_transactions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.coin_transactions (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    transaction_type character varying(10) NOT NULL,
    amount bigint NOT NULL,
    balance_before bigint NOT NULL,
    balance_after bigint NOT NULL,
    reason character varying(40) NOT NULL,
    reference_id character varying(100),
    description character varying(500),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    idempotency_key character varying(100),
    CONSTRAINT chk_coin_transaction_balance CHECK (((((transaction_type)::text = 'CREDIT'::text) AND (balance_after = (balance_before + amount))) OR (((transaction_type)::text = 'DEBIT'::text) AND (balance_after = (balance_before - amount))))),
    CONSTRAINT coin_transactions_amount_check CHECK ((amount > 0)),
    CONSTRAINT coin_transactions_balance_after_check CHECK ((balance_after >= 0)),
    CONSTRAINT coin_transactions_balance_before_check CHECK ((balance_before >= 0)),
    CONSTRAINT coin_transactions_transaction_type_check CHECK (((transaction_type)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying])::text[])))
);


ALTER TABLE public.coin_transactions OWNER TO postgres;

--
-- Name: coin_transactions_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.coin_transactions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.coin_transactions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: coin_wallets; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.coin_wallets (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    balance bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT coin_wallets_balance_check CHECK ((balance >= 0))
);


ALTER TABLE public.coin_wallets OWNER TO postgres;

--
-- Name: coin_wallets_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.coin_wallets ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.coin_wallets_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: email_otps; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.email_otps (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    otp_hash character varying(100) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.email_otps OWNER TO postgres;

--
-- Name: email_otps_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.email_otps_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.email_otps_id_seq OWNER TO postgres;

--
-- Name: email_otps_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.email_otps_id_seq OWNED BY public.email_otps.id;


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


ALTER TABLE public.flyway_schema_history OWNER TO postgres;

--
-- Name: payment_transactions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.payment_transactions (
    id bigint NOT NULL,
    txn_id character varying(100) NOT NULL,
    user_id bigint NOT NULL,
    purpose character varying(30) NOT NULL,
    product_id bigint,
    amount numeric(10,2) NOT NULL,
    currency character varying(3) DEFAULT 'INR'::character varying NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    payu_payment_id character varying(100),
    payment_mode character varying(50),
    failure_reason character varying(500),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    verified_at timestamp with time zone,
    purchased_bonus_coins bigint,
    purchased_coins bigint,
    CONSTRAINT chk_payment_product_id CHECK (((product_id IS NULL) OR (product_id > 0))),
    CONSTRAINT payment_transactions_amount_check CHECK ((amount > (0)::numeric)),
    CONSTRAINT payment_transactions_currency_check CHECK (((currency)::text = 'INR'::text)),
    CONSTRAINT payment_transactions_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['COIN_PACK'::character varying, 'RED_PACKET'::character varying, 'VIP'::character varying])::text[]))),
    CONSTRAINT payment_transactions_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.payment_transactions OWNER TO postgres;

--
-- Name: payment_transactions_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.payment_transactions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.payment_transactions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: red_packet_products; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.red_packet_products (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    coin_value bigint NOT NULL,
    price_in_rupees numeric(10,2) NOT NULL,
    image_url character varying(1000),
    image_public_id character varying(255),
    active boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT red_packet_products_coin_value_check CHECK ((coin_value > 0)),
    CONSTRAINT red_packet_products_price_in_rupees_check CHECK ((price_in_rupees > (0)::numeric)),
    CONSTRAINT red_packet_products_sort_order_check CHECK ((sort_order >= 0))
);


ALTER TABLE public.red_packet_products OWNER TO postgres;

--
-- Name: red_packet_products_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.red_packet_products ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.red_packet_products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: shop_categories; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.shop_categories (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    image_url character varying(1000),
    display_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_shop_categories_display_order CHECK ((display_order >= 0))
);


ALTER TABLE public.shop_categories OWNER TO postgres;

--
-- Name: shop_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.shop_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.shop_categories_id_seq OWNER TO postgres;

--
-- Name: shop_categories_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.shop_categories_id_seq OWNED BY public.shop_categories.id;


--
-- Name: shop_products; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.shop_products (
    id bigint NOT NULL,
    category_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    description character varying(2000),
    price numeric(12,2) NOT NULL,
    rarity character varying(20) DEFAULT 'COMMON'::character varying NOT NULL,
    asset_url character varying(1000),
    display_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    asset_type character varying(20),
    CONSTRAINT chk_shop_products_display_order CHECK ((display_order >= 0)),
    CONSTRAINT chk_shop_products_price CHECK ((price >= (0)::numeric)),
    CONSTRAINT chk_shop_products_rarity CHECK (((rarity)::text = ANY ((ARRAY['COMMON'::character varying, 'RARE'::character varying, 'EPIC'::character varying, 'LEGENDARY'::character varying])::text[])))
);


ALTER TABLE public.shop_products OWNER TO postgres;

--
-- Name: shop_products_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.shop_products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.shop_products_id_seq OWNER TO postgres;

--
-- Name: shop_products_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.shop_products_id_seq OWNED BY public.shop_products.id;


--
-- Name: user_equipped_cosmetics; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.user_equipped_cosmetics (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    cosmetic_id bigint NOT NULL,
    cosmetic_type character varying(20) NOT NULL,
    equipped_at timestamp with time zone NOT NULL
);


ALTER TABLE public.user_equipped_cosmetics OWNER TO postgres;

--
-- Name: user_equipped_cosmetics_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.user_equipped_cosmetics ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_equipped_cosmetics_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_favorite_games; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.user_favorite_games (
    user_id bigint NOT NULL,
    game character varying(30) NOT NULL,
    game_order integer NOT NULL
);


ALTER TABLE public.user_favorite_games OWNER TO postgres;

--
-- Name: user_red_packet_inventory; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.user_red_packet_inventory (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    red_packet_product_id bigint NOT NULL,
    quantity bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT user_red_packet_inventory_quantity_check CHECK ((quantity >= 0))
);


ALTER TABLE public.user_red_packet_inventory OWNER TO postgres;

--
-- Name: user_red_packet_inventory_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.user_red_packet_inventory ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_red_packet_inventory_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_unlocked_cosmetics; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.user_unlocked_cosmetics (
    id bigint NOT NULL,
    unlocked_at timestamp(6) with time zone NOT NULL,
    cosmetic_id bigint NOT NULL,
    user_id bigint NOT NULL
);


ALTER TABLE public.user_unlocked_cosmetics OWNER TO postgres;

--
-- Name: user_unlocked_cosmetics_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.user_unlocked_cosmetics ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_unlocked_cosmetics_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    full_name character varying(100),
    gender character varying(20),
    date_of_birth date,
    email_verified boolean DEFAULT false NOT NULL,
    profile_completed boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    level integer DEFAULT 0 NOT NULL,
    exp_points bigint DEFAULT 0 NOT NULL,
    charm_points bigint DEFAULT 0 NOT NULL,
    vip_points bigint DEFAULT 0 NOT NULL,
    avatar_url character varying(1000),
    banner_url character varying(1000),
    name_font character varying(30),
    bio character varying(120),
    avatar_background character varying(30),
    avatar_skin_tone character varying(30),
    avatar_outfit character varying(30),
    avatar_hair character varying(30),
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    avatar_public_id character varying(255),
    banner_public_id character varying(255),
    vip_active boolean DEFAULT false NOT NULL,
    vip_expires_at timestamp with time zone,
    vip_level integer DEFAULT 1 NOT NULL,
    admin_vip_level integer,
    CONSTRAINT chk_users_role CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ADMIN'::character varying, 'SUPER_ADMIN'::character varying])::text[])))
);


ALTER TABLE public.users OWNER TO postgres;

--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.users_id_seq OWNER TO postgres;

--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;


--
-- Name: vip_cosmetics; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.vip_cosmetics (
    id bigint NOT NULL,
    vip_level integer NOT NULL,
    cosmetic_type character varying(20) NOT NULL,
    format character varying(10) NOT NULL,
    public_id character varying(255) NOT NULL,
    secure_url text NOT NULL,
    original_filename character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_vip_cosmetic_format CHECK ((((format)::text = 'SVGA'::text) OR (((format)::text = 'PNG'::text) AND ((cosmetic_type)::text = 'BADGE'::text)))),
    CONSTRAINT vip_cosmetics_cosmetic_type_check CHECK (((cosmetic_type)::text = ANY ((ARRAY['FRAME'::character varying, 'BUBBLE'::character varying, 'ENTRY'::character varying, 'BADGE'::character varying])::text[]))),
    CONSTRAINT vip_cosmetics_format_check CHECK (((format)::text = ANY ((ARRAY['SVGA'::character varying, 'PNG'::character varying])::text[]))),
    CONSTRAINT vip_cosmetics_vip_level_check CHECK (((vip_level >= 1) AND (vip_level <= 7)))
);


ALTER TABLE public.vip_cosmetics OWNER TO postgres;

--
-- Name: vip_cosmetics_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.vip_cosmetics_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.vip_cosmetics_id_seq OWNER TO postgres;

--
-- Name: vip_cosmetics_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.vip_cosmetics_id_seq OWNED BY public.vip_cosmetics.id;


--
-- Name: voice_room_chat_messages; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.voice_room_chat_messages (
    id bigint NOT NULL,
    room_id bigint NOT NULL,
    sender_user_id bigint NOT NULL,
    ciphertext text NOT NULL,
    nonce character varying(64) NOT NULL,
    encryption_key_version integer NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    edited_at timestamp with time zone,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_voice_chat_ciphertext CHECK ((length(ciphertext) > 0)),
    CONSTRAINT chk_voice_chat_key_version CHECK ((encryption_key_version > 0)),
    CONSTRAINT chk_voice_chat_nonce CHECK ((length((nonce)::text) > 0))
);


ALTER TABLE public.voice_room_chat_messages OWNER TO postgres;

--
-- Name: voice_room_chat_messages_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.voice_room_chat_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.voice_room_chat_messages_id_seq OWNER TO postgres;

--
-- Name: voice_room_chat_messages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.voice_room_chat_messages_id_seq OWNED BY public.voice_room_chat_messages.id;


--
-- Name: voice_room_chat_receipts; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.voice_room_chat_receipts (
    id bigint NOT NULL,
    message_id bigint NOT NULL,
    user_id bigint NOT NULL,
    delivered_at timestamp with time zone,
    read_at timestamp with time zone
);


ALTER TABLE public.voice_room_chat_receipts OWNER TO postgres;

--
-- Name: voice_room_chat_receipts_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.voice_room_chat_receipts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.voice_room_chat_receipts_id_seq OWNER TO postgres;

--
-- Name: voice_room_chat_receipts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.voice_room_chat_receipts_id_seq OWNED BY public.voice_room_chat_receipts.id;


--
-- Name: voice_room_participants; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.voice_room_participants (
    id bigint NOT NULL,
    room_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role character varying(20) DEFAULT 'MEMBER'::character varying NOT NULL,
    status character varying(20) DEFAULT 'JOINED'::character varying NOT NULL,
    is_muted boolean DEFAULT false NOT NULL,
    joined_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    left_at timestamp with time zone,
    CONSTRAINT chk_voice_room_participant_role CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'MODERATOR'::character varying, 'MEMBER'::character varying])::text[]))),
    CONSTRAINT chk_voice_room_participant_status CHECK (((status)::text = ANY ((ARRAY['JOINED'::character varying, 'LEFT'::character varying])::text[])))
);


ALTER TABLE public.voice_room_participants OWNER TO postgres;

--
-- Name: voice_room_participants_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.voice_room_participants_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.voice_room_participants_id_seq OWNER TO postgres;

--
-- Name: voice_room_participants_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.voice_room_participants_id_seq OWNED BY public.voice_room_participants.id;


--
-- Name: voice_rooms; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.voice_rooms (
    id bigint NOT NULL,
    owner_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    room_type character varying(20) DEFAULT 'VOICE'::character varying NOT NULL,
    is_private boolean DEFAULT false NOT NULL,
    max_participants integer DEFAULT 8 NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_voice_rooms_max_participants CHECK (((max_participants >= 2) AND (max_participants <= 50))),
    CONSTRAINT chk_voice_rooms_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CLOSED'::character varying])::text[]))),
    CONSTRAINT chk_voice_rooms_type CHECK (((room_type)::text = ANY ((ARRAY['VOICE'::character varying, 'WATCH'::character varying, 'KHARIDARI'::character varying])::text[])))
);


ALTER TABLE public.voice_rooms OWNER TO postgres;

--
-- Name: voice_rooms_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.voice_rooms_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.voice_rooms_id_seq OWNER TO postgres;

--
-- Name: voice_rooms_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.voice_rooms_id_seq OWNED BY public.voice_rooms.id;


--
-- Name: admin_accounts id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.admin_accounts ALTER COLUMN id SET DEFAULT nextval('public.admin_accounts_id_seq'::regclass);


--
-- Name: chat_conversation_participants id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_conversation_participants ALTER COLUMN id SET DEFAULT nextval('public.chat_conversation_participants_id_seq'::regclass);


--
-- Name: chat_conversations id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_conversations ALTER COLUMN id SET DEFAULT nextval('public.chat_conversations_id_seq'::regclass);


--
-- Name: chat_inspection_audit id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_inspection_audit ALTER COLUMN id SET DEFAULT nextval('public.chat_inspection_audit_id_seq'::regclass);


--
-- Name: chat_message_receipts id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_message_receipts ALTER COLUMN id SET DEFAULT nextval('public.chat_message_receipts_id_seq'::regclass);


--
-- Name: chat_messages id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_messages ALTER COLUMN id SET DEFAULT nextval('public.chat_messages_id_seq'::regclass);


--
-- Name: email_otps id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.email_otps ALTER COLUMN id SET DEFAULT nextval('public.email_otps_id_seq'::regclass);


--
-- Name: shop_categories id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shop_categories ALTER COLUMN id SET DEFAULT nextval('public.shop_categories_id_seq'::regclass);


--
-- Name: shop_products id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shop_products ALTER COLUMN id SET DEFAULT nextval('public.shop_products_id_seq'::regclass);


--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);


--
-- Name: vip_cosmetics id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vip_cosmetics ALTER COLUMN id SET DEFAULT nextval('public.vip_cosmetics_id_seq'::regclass);


--
-- Name: voice_room_chat_messages id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_messages ALTER COLUMN id SET DEFAULT nextval('public.voice_room_chat_messages_id_seq'::regclass);


--
-- Name: voice_room_chat_receipts id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_receipts ALTER COLUMN id SET DEFAULT nextval('public.voice_room_chat_receipts_id_seq'::regclass);


--
-- Name: voice_room_participants id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_participants ALTER COLUMN id SET DEFAULT nextval('public.voice_room_participants_id_seq'::regclass);


--
-- Name: voice_rooms id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_rooms ALTER COLUMN id SET DEFAULT nextval('public.voice_rooms_id_seq'::regclass);


--
-- Data for Name: admin_accounts; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.admin_accounts (id, username, password_hash, role, enabled, created_at) FROM stdin;
1	superadmin	$2a$10$7ZZX2DTFcLG5cW4zsCMLPOP1LUOLZUP10h7BK7/B/7xAATLx4fCqa	SUPER_ADMIN	t	2026-10-30 08:34:59.626882
2	admin	$2a$10$8tMzNs9CsVKv9AEYvYnNTOLPANvFGbIykfcVyRuEhxrnsW3/2uy2i	ADMIN	t	2026-10-30 08:34:59.828234
3	moderator	$2a$10$8oAx5HuBY1VESZQaEwXL9euzrfiZMgg.ivlwLlngcShcLaktOxdsq	MODERATOR	t	2026-10-30 08:35:00.016977
\.


--
-- Data for Name: chat_conversation_participants; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.chat_conversation_participants (id, conversation_id, user_id, joined_at, last_read_message_id) FROM stdin;
1	1	2	2026-11-01 14:43:45.281816+05:30	\N
2	1	1	2026-11-01 14:43:45.28636+05:30	\N
3	2	4	2026-11-01 17:37:22.017565+05:30	\N
4	2	2	2026-11-01 17:37:22.027463+05:30	\N
5	3	4	2026-11-01 17:38:10.781145+05:30	\N
6	3	1	2026-11-01 17:38:10.781145+05:30	\N
\.


--
-- Data for Name: chat_conversations; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.chat_conversations (id, created_at, updated_at) FROM stdin;
1	2026-11-01 14:43:45.213303+05:30	2026-11-01 14:43:45.213303+05:30
2	2026-11-01 17:37:21.978877+05:30	2026-11-01 17:37:21.978877+05:30
3	2026-11-01 17:38:10.777919+05:30	2026-11-01 17:38:10.777919+05:30
\.


--
-- Data for Name: chat_inspection_audit; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.chat_inspection_audit (id, admin_account_id, conversation_id, reason, action, created_at) FROM stdin;
\.


--
-- Data for Name: chat_message_receipts; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.chat_message_receipts (id, message_id, user_id, delivered_at, read_at) FROM stdin;
1	1	1	\N	2026-11-01 14:44:17.784992+05:30
2	2	2	\N	2026-11-01 14:44:26.385329+05:30
3	3	2	\N	2026-11-01 14:44:47.683516+05:30
4	5	1	\N	2026-11-01 14:45:21.614604+05:30
5	6	1	\N	2026-11-01 14:45:21.614604+05:30
6	7	1	\N	2026-11-01 14:45:21.614604+05:30
7	8	2	\N	2026-11-01 14:45:38.760253+05:30
9	13	1	\N	2026-11-01 17:38:23.453312+05:30
10	14	4	\N	2026-11-01 17:43:32.932256+05:30
11	15	4	\N	2026-11-01 17:43:32.932256+05:30
12	16	4	\N	2026-11-01 17:43:32.932256+05:30
8	12	2	\N	2026-11-01 22:22:58.114944+05:30
13	17	4	\N	\N
14	18	4	\N	\N
\.


--
-- Data for Name: chat_messages; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.chat_messages (id, conversation_id, sender_user_id, ciphertext, nonce, encryption_key_version, created_at, edited_at, deleted_at, message_type, media_storage_key, media_content_type, media_size_bytes) FROM stdin;
1	1	2	2yoT0nxDFJtiZHkEYPR/EAOxzzcTc/t6	GT5yfiJomH1e6zL8	1	2026-11-01 14:43:51.564829+05:30	\N	\N	TEXT	\N	\N	\N
2	1	1	568DGBhIsvnZ6RAvoZlsMud2pg==	lEHsNFaFdvjX+MNE	1	2026-11-01 14:44:21.189085+05:30	\N	\N	TEXT	\N	\N	\N
3	1	1	8vwL3ze5H89qfk20oS8sYaXRmYCj97nZjg==	K0CqrD+L9ohzAZQC	1	2026-11-01 14:44:32.339239+05:30	\N	\N	TEXT	\N	\N	\N
5	1	2	pjHIl625HDUj2QpAIrdoUHVFaT0=	Q1D328SVfipHoDWL	1	2026-11-01 14:45:02.042117+05:30	\N	\N	TEXT	\N	\N	\N
6	1	2	gWt0ghj3s7Y/FpkHcNS+gGK1Hg==	95qX2hVWhAsl5YiX	1	2026-11-01 14:45:04.248893+05:30	\N	\N	TEXT	\N	\N	\N
7	1	2	vfciFak3d683Ps1ylXikX0UZxpYe	S6LQabTJqamV891z	1	2026-11-01 14:45:12.923535+05:30	\N	\N	TEXT	\N	\N	\N
8	1	1	qhmLwVubH0gpCesYFmSigSNWT3c+1A==	coO9SJgxsNDIGvpr	1	2026-11-01 14:45:26.541409+05:30	\N	\N	TEXT	\N	\N	\N
12	2	4	2DsjzQVvJT8wlxy1gtQSZpLO	iaf4SMBj3TKP6aKy	1	2026-11-01 17:37:25.197676+05:30	\N	\N	TEXT	\N	\N	\N
13	3	4	UeZNBr3F6pDI9ZLmc6DTcYqn	OOKqQ/40pOvVEslL	1	2026-11-01 17:38:14.008299+05:30	\N	\N	TEXT	\N	\N	\N
14	3	1	8llc/xUL0ClQjUu+nnAlfTvXdZwg+pkY	6o3amG/isr3pDFFU	1	2026-11-01 17:38:27.967413+05:30	\N	\N	TEXT	\N	\N	\N
15	3	1	pG6VwLGDv6n+6DhrIajQHXc+	4MrOdfouTolMKeLN	1	2026-11-01 17:38:45.998089+05:30	\N	\N	TEXT	\N	\N	\N
16	3	1	\N	\N	1	2026-11-01 17:42:41.380698+05:30	\N	\N	IMAGE	de005197-e12c-4822-8c29-e273ad8e107d	image/jpeg	2046417
17	2	2	oLSo1EMJ+QWWLhgrxeyo+8aw4zk=	TJ5XkDQSwHz/zc6A	1	2026-11-01 22:22:59.045433+05:30	\N	\N	TEXT	\N	\N	\N
18	2	2	\N	\N	1	2026-11-01 22:23:04.452064+05:30	\N	\N	IMAGE	dd0d419c-4e1e-446d-ad45-a2b76dcaf438	image/png	190397
\.


--
-- Data for Name: coin_packs; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.coin_packs (id, name, coins, price_in_rupees, bonus_coins, bonus_percentage, badge, image_url, image_public_id, active, sort_order, created_at, updated_at) FROM stdin;
1	best value	600	49.00	20	10	POPULAR	https://res.cloudinary.com/dinsovoq/image/upload/v1793460732/panchayat/wallet/coin-packs/cfj0u7vrn1k1dspvutph.png	panchayat/wallet/coin-packs/cfj0u7vrn1k1dspvutph	t	1	2026-10-31 20:59:03.544638	2026-10-31 21:37:48.566605
2	best seller	2000	149.00	200	10	\N	https://res.cloudinary.com/dinsovoq/image/upload/v1793463027/panchayat/wallet/coin-packs/mvsjanv2heznvz3zpcjq.png	panchayat/wallet/coin-packs/mvsjanv2heznvz3zpcjq	t	1	2026-10-31 21:40:18.786623	2026-10-31 21:40:29.950012
3	Easy to buy	6000	449.00	200	15	POPULAR	https://res.cloudinary.com/dinsovoq/image/upload/v1793463096/panchayat/wallet/coin-packs/j1sjbft7uhcpya36ufri.png	panchayat/wallet/coin-packs/j1sjbft7uhcpya36ufri	t	1	2026-10-31 21:41:28.538803	2026-10-31 21:46:37.701998
4	big money	12000	849.00	500	15	\N	https://res.cloudinary.com/dinsovoq/image/upload/v1793463460/panchayat/wallet/coin-packs/w5t8gp9w2coqkun0iooo.png	panchayat/wallet/coin-packs/w5t8gp9w2coqkun0iooo	t	1	2026-10-31 21:47:29.971993	2026-10-31 21:47:41.506116
5	Make money	30000	1999.00	1000	23	\N	https://res.cloudinary.com/dinsovoq/image/upload/v1793463545/panchayat/wallet/coin-packs/scucvt9k3pp4b2wmiw9x.png	panchayat/wallet/coin-packs/scucvt9k3pp4b2wmiw9x	t	1	2026-10-31 21:48:58.594973	2026-10-31 21:49:06.909222
6	RICHI RICH	65000	3999.00	5000	33	\N	https://res.cloudinary.com/dinsovoq/image/upload/v1793463618/panchayat/wallet/coin-packs/hbkhu5tl0akcinwrfnju.png	panchayat/wallet/coin-packs/hbkhu5tl0akcinwrfnju	t	1	2026-10-31 21:50:05.071004	2026-10-31 21:50:20.214913
7	VAST WEALTH	140000	7999.00	10000	44	\N	https://res.cloudinary.com/dinsovoq/image/upload/v1793463679/panchayat/wallet/coin-packs/epcmtz3p2nnaqxxhkyx5.png	panchayat/wallet/coin-packs/epcmtz3p2nnaqxxhkyx5	t	1	2026-10-31 21:51:11.072853	2026-10-31 21:51:20.851564
\.


--
-- Data for Name: coin_transactions; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.coin_transactions (id, user_id, transaction_type, amount, balance_before, balance_after, reason, reference_id, description, created_at, idempotency_key) FROM stdin;
1	2	CREDIT	7777777777	0	7777777777	ADMIN_GRANT	hhgyuy	ytujgjgli	2026-10-31 23:56:09.545978+05:30	db3b2bb4-5147-4442-b002-3096e3118637
2	1	CREDIT	112345688	0	112345688	ADMIN_GRANT	rthhhujh	hghhhjhgjfhf	2026-10-31 23:57:49.47528+05:30	08196aab-17ba-4f41-b42b-6d52347f20e8
\.


--
-- Data for Name: coin_wallets; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.coin_wallets (id, user_id, balance, created_at, updated_at) FROM stdin;
1	2	7777777777	2026-10-31 23:56:09.412292+05:30	2026-10-31 23:56:09.554205+05:30
2	1	112345688	2026-10-31 23:57:49.468726+05:30	2026-10-31 23:57:49.47528+05:30
\.


--
-- Data for Name: email_otps; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.email_otps (id, email, otp_hash, expires_at, attempts, created_at) FROM stdin;
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
1	1	create initial schema	SQL	V1__create_initial_schema.sql	181087459	postgres	2026-10-29 16:17:04.628445	168	t
2	2	add user stats	SQL	V2__add_user_stats.sql	1553430445	postgres	2026-10-29 20:55:35.36063	66	t
3	3	add user profile customization	SQL	V3__add_user_profile_customization.sql	-113335991	postgres	2026-10-29 22:31:35.632971	88	t
4	4	create shop tables	SQL	V4__create_shop_tables.sql	-1358170374	postgres	2026-10-30 00:29:01.325949	91	t
5	5	add user role	SQL	V5__add_user_role.sql	1542047424	postgres	2026-10-30 00:29:01.447096	10	t
6	6	create voice rooms	SQL	V6__create_voice_rooms.sql	-1955216601	postgres	2026-10-30 22:24:07.124415	157	t
7	7	create voice room participants	SQL	V7__create_voice_room_participants.sql	1465940058	postgres	2026-10-30 23:12:17.929594	101	t
8	8	add cloudinary public ids	SQL	V8__add_cloudinary_public_ids.sql	-265650601	postgres	2026-10-31 15:52:23.422237	42	t
9	9	create coin packs	SQL	V9__create_coin_packs.sql	1568227275	postgres	2026-10-31 19:54:52.107948	133	t
10	10	create red packet products	SQL	V10__create_red_packet_products.sql	-403684563	postgres	2026-10-31 19:56:09.139701	40	t
11	11	create user red packet inventory	SQL	V11__create_user_red_packet_inventory.sql	-1911956912	postgres	2026-10-31 19:57:11.514735	36	t
12	12	create coin wallet tables	SQL	V12__create_coin_wallet_tables.sql	-1743683310	postgres	2026-10-31 23:07:26.88728	116	t
13	13	add coin transaction idempotency	SQL	V13__add_coin_transaction_idempotency.sql	-886433846	postgres	2026-10-31 23:07:27.044292	7	t
14	14	add vip membership fields	SQL	V14__add_vip_membership_fields.sql	2092292654	postgres	2026-10-31 23:40:03.812198	88	t
15	15	create vip cosmetics	SQL	V15__create_vip_cosmetics.sql	-2020542306	postgres	2026-11-01 00:39:27.028148	63	t
16	16	create chat system	SQL	V16__create_chat_system.sql	-945842441	postgres	2026-11-01 13:34:53.338005	117	t
17	17	add chat image support	SQL	V17__add_chat_image_support.sql	1253310569	postgres	2026-11-01 13:34:53.491212	10	t
18	18	create payment transactions	SQL	V18__create_payment_transactions.sql	1121949086	postgres	2026-11-02 01:29:08.073023	258	t
\.


--
-- Data for Name: payment_transactions; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.payment_transactions (id, txn_id, user_id, purpose, product_id, amount, currency, status, payu_payment_id, payment_mode, failure_reason, created_at, updated_at, verified_at, purchased_bonus_coins, purchased_coins) FROM stdin;
\.


--
-- Data for Name: red_packet_products; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.red_packet_products (id, name, coin_value, price_in_rupees, image_url, image_public_id, active, sort_order, created_at, updated_at) FROM stdin;
1	gold red packet	10000	499.00	https://res.cloudinary.com/dinsovoq/image/upload/v1793461267/panchayat/wallet/red-packets/kqnactqxvobclzaavkco.png	panchayat/wallet/red-packets/kqnactqxvobclzaavkco	t	1	2026-10-31 21:03:57.867917	2026-10-31 21:11:09.235212
2	Royal red packet	50000	1999.00	https://res.cloudinary.com/dinsovoq/image/upload/v1793462289/panchayat/wallet/red-packets/k9nsm3t9pn8dmpbnzn2w.png	panchayat/wallet/red-packets/k9nsm3t9pn8dmpbnzn2w	t	1	2026-10-31 21:27:58.284267	2026-10-31 21:28:11.066271
3	Maharaja red packet	80000	2199.00	https://res.cloudinary.com/dinsovoq/image/upload/v1793462352/panchayat/wallet/red-packets/ykk0mruapsrpfr64yccd.png	panchayat/wallet/red-packets/ykk0mruapsrpfr64yccd	t	1	2026-10-31 21:28:46.364634	2026-10-31 21:29:13.715212
\.


--
-- Data for Name: shop_categories; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.shop_categories (id, name, description, image_url, display_order, active, created_at, updated_at) FROM stdin;
1	red packet	10 k red packet		0	t	2026-10-30 14:40:54.252704+05:30	2026-10-30 14:40:54.252704+05:30
2	chat bubble	chat bubble		3	t	2026-10-30 14:41:48.546404+05:30	2026-10-30 14:41:48.546404+05:30
3	RING			0	t	2026-10-30 16:03:05.495827+05:30	2026-10-30 16:03:05.495827+05:30
\.


--
-- Data for Name: shop_products; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.shop_products (id, category_id, name, description, price, rarity, asset_url, display_order, active, created_at, updated_at, asset_type) FROM stdin;
1	2	chat got		100.00	COMMON	https://res.cloudinary.com/dinsovoq/raw/upload/v1793354548/panchayat/shop-products/9fafb8f4-a6d8-4bf4-8b52-7a295813212b.svga	3	f	2026-10-30 15:16:32.452167+05:30	2026-10-30 15:41:09.381778+05:30	\N
2	3	red tulip ring		9999.00	COMMON	https://res.cloudinary.com/dinsovoq/image/upload/v1793356415/panchayat/shop-products/cc219b86-31eb-462b-b934-ecc7ac1d5b67.jpg	0	t	2026-10-30 16:03:39.335102+05:30	2026-10-30 16:03:39.335102+05:30	\N
3	3	white moon		19999.00	COMMON	https://res.cloudinary.com/dinsovoq/image/upload/v1793356462/panchayat/shop-products/f28c584e-6521-4780-8c85-418261d11ea5.jpg	0	t	2026-10-30 16:04:32.273932+05:30	2026-10-30 16:04:32.273932+05:30	\N
4	3	pearl ring		5555.00	COMMON	https://res.cloudinary.com/dinsovoq/image/upload/v1793356544/panchayat/shop-products/bf415af4-0c38-4b4a-a78f-923bbf250236.jpg	0	t	2026-10-30 16:05:46.294965+05:30	2026-10-30 16:05:46.294965+05:30	\N
5	3	green valley ring		8888.00	COMMON	https://res.cloudinary.com/dinsovoq/image/upload/v1793356590/panchayat/shop-products/338ae2fd-e2ac-4222-8c48-4098335498c3.jpg	0	t	2026-10-30 16:06:32.708338+05:30	2026-10-30 16:06:54.81533+05:30	\N
6	3	purple gate ring		20000.00	COMMON	https://res.cloudinary.com/dinsovoq/image/upload/v1793356713/panchayat/shop-products/9cae34a6-40e9-44d1-95b2-2b2c709e8351.jpg	0	t	2026-10-30 16:08:35.47759+05:30	2026-10-30 16:08:44.503055+05:30	\N
7	3	butterfly ring		40000.00	COMMON	https://res.cloudinary.com/dinsovoq/image/upload/v1793356910/panchayat/shop-products/2abc46c0-d18f-4a59-842e-c9940235bb1c.jpg	0	t	2026-10-30 16:11:53.254182+05:30	2026-10-30 16:11:53.254182+05:30	\N
8	3	white flower ring		8888.00	COMMON	https://res.cloudinary.com/dinsovoq/image/upload/v1793357012/panchayat/shop-products/8b80fbaa-795e-4998-be36-959c3f1ab943.jpg	0	t	2026-10-30 16:13:34.359346+05:30	2026-10-30 16:13:34.359346+05:30	\N
\.


--
-- Data for Name: user_equipped_cosmetics; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.user_equipped_cosmetics (id, user_id, cosmetic_id, cosmetic_type, equipped_at) FROM stdin;
4	2	4	BADGE	2026-11-01 03:43:47.8061+05:30
11	1	2	BUBBLE	2026-11-01 22:13:41.461271+05:30
12	1	1	FRAME	2026-11-01 22:13:46.289339+05:30
13	1	3	ENTRY	2026-11-01 22:13:48.701166+05:30
\.


--
-- Data for Name: user_favorite_games; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.user_favorite_games (user_id, game, game_order) FROM stdin;
2	ANTAKSHARI	0
1	ludo	0
1	micgrab	1
\.


--
-- Data for Name: user_red_packet_inventory; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.user_red_packet_inventory (id, user_id, red_packet_product_id, quantity, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: user_unlocked_cosmetics; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.user_unlocked_cosmetics (id, unlocked_at, cosmetic_id, user_id) FROM stdin;
1	2026-11-01 18:32:56.292576+05:30	4	1
2	2026-11-01 18:32:56.314967+05:30	2	1
3	2026-11-01 18:32:56.32115+05:30	3	1
4	2026-11-01 18:32:56.327778+05:30	1	1
5	2026-11-01 18:35:13.295611+05:30	4	2
6	2026-11-01 18:35:13.309408+05:30	2	2
7	2026-11-01 18:35:13.316791+05:30	3	2
8	2026-11-01 18:35:13.316791+05:30	1	2
\.


--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.users (id, email, full_name, gender, date_of_birth, email_verified, profile_completed, created_at, updated_at, level, exp_points, charm_points, vip_points, avatar_url, banner_url, name_font, bio, avatar_background, avatar_skin_tone, avatar_outfit, avatar_hair, role, avatar_public_id, banner_public_id, vip_active, vip_expires_at, vip_level, admin_vip_level) FROM stdin;
2	aryan10aee@gmail.com	Aryann<	Male	2008-10-03	t	t	2026-10-29 19:01:46.850869+05:30	2026-10-31 17:48:15.655311+05:30	0	0	0	0	https://res.cloudinary.com/dinsovoq/image/upload/v1793448422/panchayat/users/2/avatar/icx6z2hxhpstonrmsvf9.jpg	\N	default	Stefan gareeeb	#2f4a63	#e8b597	#6fe0a6	\N	USER	panchayat/users/2/avatar/icx6z2hxhpstonrmsvf9	\N	t	2026-12-01 03:43:34.769143+05:30	1	\N
3	vanshthakur.x@gmail.com	vansh	Male	2008-10-20	t	t	2026-10-29 23:02:38.981406+05:30	2026-10-29 23:02:52.739961+05:30	0	0	0	0	\N	\N	CLASSIC	\N	\N	\N	\N	\N	USER	\N	\N	f	\N	1	\N
1	bharatpathania700@gmail.com	Bharat	Male	2005-03-27	t	t	2026-10-29 16:21:56.336597+05:30	2026-10-31 17:32:59.129579+05:30	0	0	0	0	https://res.cloudinary.com/dinsovoq/image/upload/v1793448177/panchayat/users/1/avatar/xobwsre2qv8j8wxc8x9i.webp	https://res.cloudinary.com/dinsovoq/image/upload/v1793447461/panchayat/users/1/banner/ylqzkleyknn2brgyp8fk.jpg	cinzel	heyy wsupp>>	\N	\N	\N	\N	USER	panchayat/users/1/avatar/xobwsre2qv8j8wxc8x9i	panchayat/users/1/banner/ylqzkleyknn2brgyp8fk	t	2026-12-01 14:37:09.167848+05:30	1	\N
4	studiopathania@gmail.com	your dad	Male	2005-03-27	t	t	2026-10-31 00:14:56.08164+05:30	2026-11-01 14:40:19.590901+05:30	0	0	0	0	\N	\N	cinzel	\N	\N	\N	#8fa8ff	\N	USER	\N	\N	f	\N	1	\N
\.


--
-- Data for Name: vip_cosmetics; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.vip_cosmetics (id, vip_level, cosmetic_type, format, public_id, secure_url, original_filename, created_at, updated_at) FROM stdin;
1	1	FRAME	SVGA	panchayat/vip/vip1/frame_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474453/panchayat/vip/vip1/frame_svga	f.svga	2026-11-01 00:50:54.54161+05:30	2026-11-01 00:50:54.54161+05:30
2	1	BUBBLE	SVGA	panchayat/vip/vip1/bubble_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474525/panchayat/vip/vip1/bubble_svga	chat.svga	2026-11-01 00:52:07.348471+05:30	2026-11-01 00:52:07.348471+05:30
3	1	ENTRY	SVGA	panchayat/vip/vip1/entry_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474563/panchayat/vip/vip1/entry_svga	entry.svga	2026-11-01 00:52:45.175937+05:30	2026-11-01 00:52:45.175937+05:30
4	1	BADGE	SVGA	panchayat/vip/vip1/badge_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474663/panchayat/vip/vip1/badge_svga	badge1.svga	2026-11-01 00:54:24.626081+05:30	2026-11-01 00:54:24.626081+05:30
5	2	FRAME	SVGA	panchayat/vip/vip2/frame_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474709/panchayat/vip/vip2/frame_svga	2f.svga	2026-11-01 00:55:10.800451+05:30	2026-11-01 00:55:10.800451+05:30
6	2	BUBBLE	SVGA	panchayat/vip/vip2/bubble_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474728/panchayat/vip/vip2/bubble_svga	chat.svga	2026-11-01 00:55:29.814868+05:30	2026-11-01 00:55:29.814868+05:30
7	2	ENTRY	SVGA	panchayat/vip/vip2/entry_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474754/panchayat/vip/vip2/entry_svga	entry.svga	2026-11-01 00:55:55.919907+05:30	2026-11-01 00:55:55.919907+05:30
8	2	BADGE	SVGA	panchayat/vip/vip2/badge_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474782/panchayat/vip/vip2/badge_svga	badge2.svga	2026-11-01 00:56:23.63274+05:30	2026-11-01 00:56:23.63274+05:30
9	3	FRAME	SVGA	panchayat/vip/vip3/frame_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474881/panchayat/vip/vip3/frame_svga	3f.svga	2026-11-01 00:58:02.396839+05:30	2026-11-01 00:58:02.396839+05:30
10	3	BUBBLE	SVGA	panchayat/vip/vip3/bubble_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474936/panchayat/vip/vip3/bubble_svga	chat.svga	2026-11-01 00:58:57.672134+05:30	2026-11-01 00:58:57.672134+05:30
11	3	ENTRY	SVGA	panchayat/vip/vip3/entry_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793474968/panchayat/vip/vip3/entry_svga	entry.svga	2026-11-01 00:59:29.983929+05:30	2026-11-01 00:59:29.983929+05:30
12	3	BADGE	SVGA	panchayat/vip/vip3/badge_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475007/panchayat/vip/vip3/badge_svga	badge.svga	2026-11-01 01:00:08.979644+05:30	2026-11-01 01:00:08.979644+05:30
13	4	FRAME	SVGA	panchayat/vip/vip4/frame_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475089/panchayat/vip/vip4/frame_svga	4f.svga	2026-11-01 01:01:30.657603+05:30	2026-11-01 01:01:30.657603+05:30
14	4	BUBBLE	SVGA	panchayat/vip/vip4/bubble_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475112/panchayat/vip/vip4/bubble_svga	chat.svga	2026-11-01 01:01:54.241537+05:30	2026-11-01 01:01:54.241537+05:30
15	4	ENTRY	SVGA	panchayat/vip/vip4/entry_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475143/panchayat/vip/vip4/entry_svga	entry.svga	2026-11-01 01:02:24.688821+05:30	2026-11-01 01:02:24.688821+05:30
16	4	BADGE	SVGA	panchayat/vip/vip4/badge_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475175/panchayat/vip/vip4/badge_svga	badge.svga	2026-11-01 01:02:57.188338+05:30	2026-11-01 01:02:57.188338+05:30
17	7	FRAME	SVGA	panchayat/vip/vip7/frame_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475259/panchayat/vip/vip7/frame_svga	7f.svga	2026-11-01 01:04:21.325733+05:30	2026-11-01 01:04:21.325733+05:30
18	7	BUBBLE	SVGA	panchayat/vip/vip7/bubble_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475295/panchayat/vip/vip7/bubble_svga	chat.svga	2026-11-01 01:04:57.077448+05:30	2026-11-01 01:04:57.077448+05:30
19	7	ENTRY	SVGA	panchayat/vip/vip7/entry_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475495/panchayat/vip/vip7/entry_svga	entry.svga	2026-11-01 01:08:17.323057+05:30	2026-11-01 01:08:17.323057+05:30
20	7	BADGE	SVGA	panchayat/vip/vip7/badge_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793475522/panchayat/vip/vip7/badge_svga	badge.svga	2026-11-01 01:08:44.269582+05:30	2026-11-01 01:08:44.269582+05:30
21	6	FRAME	SVGA	panchayat/vip/vip6/frame_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793476036/panchayat/vip/vip6/frame_svga	6f.svga	2026-11-01 01:17:17.513725+05:30	2026-11-01 01:17:17.513725+05:30
22	6	BUBBLE	SVGA	panchayat/vip/vip6/bubble_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793476087/panchayat/vip/vip6/bubble_svga	chat.svga	2026-11-01 01:18:08.585787+05:30	2026-11-01 01:18:08.585787+05:30
23	6	ENTRY	SVGA	panchayat/vip/vip6/entry_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793476108/panchayat/vip/vip6/entry_svga	entry.svga	2026-11-01 01:18:30.162576+05:30	2026-11-01 01:18:30.162576+05:30
24	6	BADGE	SVGA	panchayat/vip/vip6/badge_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793476160/panchayat/vip/vip6/badge_svga	badge.svga	2026-11-01 01:19:21.91048+05:30	2026-11-01 01:19:21.91048+05:30
25	5	BUBBLE	SVGA	panchayat/vip/vip5/bubble_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793476442/panchayat/vip/vip5/bubble_svga	VIP5_Chat Bubble.svga	2026-11-01 01:24:03.483623+05:30	2026-11-01 01:24:03.483623+05:30
26	5	ENTRY	SVGA	panchayat/vip/vip5/entry_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793476502/panchayat/vip/vip5/entry_svga	VIP5_entry.svga	2026-11-01 01:25:04.372961+05:30	2026-11-01 01:25:04.372961+05:30
27	5	BADGE	SVGA	panchayat/vip/vip5/badge_svga	https://res.cloudinary.com/dinsovoq/raw/upload/v1793476539/panchayat/vip/vip5/badge_svga	5badge.svga	2026-11-01 01:25:40.662082+05:30	2026-11-01 01:25:40.662082+05:30
\.


--
-- Data for Name: voice_room_chat_messages; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.voice_room_chat_messages (id, room_id, sender_user_id, ciphertext, nonce, encryption_key_version, created_at, edited_at, deleted_at) FROM stdin;
1	9	1	Faz/f/tdtqV+z0i/mzICaKoC	HGUozz+qfdSUhFiV	1	2026-11-01 14:37:32.906727+05:30	\N	\N
2	9	2	IHUyj4d0aRUptWsIuvuG4AA0MKfd	734TiKMR6L5IDKC2	1	2026-11-01 14:37:49.080112+05:30	\N	\N
3	9	1	fuZSfgZSd9VgY19Z/5n2kQtEWvnmn7u5ynor+Qsmag==	SYbuIOOmf3zX0jOD	1	2026-11-01 14:38:02.936755+05:30	\N	\N
4	9	2	Yz6I/uIuwTqPEQuhi8U9z4GrSkf3mRoHqesh	B/+iWtSv5d1qiGOA	1	2026-11-01 14:38:06.085847+05:30	\N	\N
5	9	4	qYaWQw72A5k9jkqN2eND5V7EMqU=	PQwVSY9n7jvS6QJr	1	2026-11-01 14:40:01.657063+05:30	\N	\N
6	9	4	k44uWdzvBeqO65G66Ji3bS7t454=	Sr60KrqLhs/8dcjw	1	2026-11-01 14:40:04.154717+05:30	\N	\N
7	9	4	8o9PZRcyOrWswMsoh19mW6uFbQ==	L/SO2Xsa+oMOwYUj	1	2026-11-01 14:40:32.477287+05:30	\N	\N
8	9	1	wIEa34VLd+ucszuvxsBUOUcDAQ==	ffMH49M1jY++ZkfL	1	2026-11-01 14:44:13.33146+05:30	\N	\N
9	9	2	JRrCWuVx9kkDNqwHBPZTAFelP03VTtQJSnYd/po=	e+xgxprXWf9EvQ8K	1	2026-11-01 22:21:18.53997+05:30	\N	\N
\.


--
-- Data for Name: voice_room_chat_receipts; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.voice_room_chat_receipts (id, message_id, user_id, delivered_at, read_at) FROM stdin;
\.


--
-- Data for Name: voice_room_participants; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.voice_room_participants (id, room_id, user_id, role, status, is_muted, joined_at, left_at) FROM stdin;
1	1	2	OWNER	LEFT	f	2026-10-30 23:40:11.430819+05:30	2026-10-30 23:44:45.872576+05:30
2	2	2	OWNER	LEFT	f	2026-10-30 23:52:38.399692+05:30	2026-10-31 00:07:32.273254+05:30
4	3	4	MEMBER	LEFT	f	2026-10-31 00:22:44.458386+05:30	2026-10-31 00:25:06.826229+05:30
3	3	2	OWNER	LEFT	f	2026-10-31 00:16:09.118237+05:30	2026-10-31 00:25:13.412967+05:30
6	4	2	MEMBER	LEFT	f	2026-10-31 00:25:44.111714+05:30	2026-10-31 00:27:28.484044+05:30
5	4	4	OWNER	LEFT	f	2026-10-31 00:25:27.357777+05:30	2026-10-31 00:27:28.484044+05:30
8	5	4	MEMBER	LEFT	f	2026-10-31 00:53:44.401326+05:30	2026-10-31 00:54:14.296756+05:30
7	5	2	OWNER	LEFT	f	2026-10-31 00:53:38.991769+05:30	2026-10-31 01:15:27.1995+05:30
9	6	2	OWNER	LEFT	f	2026-10-31 13:35:09.444838+05:30	2026-10-31 13:36:30.219405+05:30
11	7	1	MEMBER	LEFT	f	2026-10-31 13:54:05.684067+05:30	2026-10-31 13:59:30.660129+05:30
10	7	2	OWNER	LEFT	f	2026-10-31 13:40:36.138546+05:30	2026-10-31 14:01:13.521566+05:30
13	8	1	MEMBER	LEFT	f	2026-10-31 14:26:12.812806+05:30	2026-10-31 14:38:08.019711+05:30
12	8	2	OWNER	LEFT	f	2026-10-31 14:26:05.332206+05:30	2026-10-31 15:08:59.912345+05:30
19	9	4	MEMBER	LEFT	f	2026-11-01 14:40:27.322411+05:30	2026-11-01 22:14:42.551302+05:30
14	9	1	OWNER	LEFT	f	2026-11-01 22:13:53.348062+05:30	2026-11-01 22:14:49.064972+05:30
15	9	2	MEMBER	LEFT	f	2026-11-01 22:23:32.37969+05:30	2026-11-01 22:23:35.743864+05:30
17	11	2	OWNER	LEFT	f	2026-11-01 14:08:28.181486+05:30	2026-11-01 14:13:41.374213+05:30
18	11	1	MEMBER	LEFT	f	2026-11-01 14:08:36.707052+05:30	2026-11-01 14:13:41.374213+05:30
16	10	1	OWNER	LEFT	f	2026-10-31 19:12:42.350611+05:30	2026-10-31 19:13:02.217141+05:30
\.


--
-- Data for Name: voice_rooms; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.voice_rooms (id, owner_id, name, description, room_type, is_private, max_participants, status, created_at, updated_at) FROM stdin;
1	2	Aryann<'s adda	\N	VOICE	f	20	CLOSED	2026-10-30 23:40:11.38247+05:30	2026-10-30 23:44:45.878628+05:30
2	2	Aryann<'s adda	\N	VOICE	f	20	CLOSED	2026-10-30 23:52:38.351049+05:30	2026-10-31 00:07:32.284972+05:30
3	2	Aryann<'s adda	\N	VOICE	f	20	CLOSED	2026-10-31 00:16:09.104416+05:30	2026-10-31 00:25:13.412967+05:30
4	4	Bharat Pathania's adda	\N	WATCH	f	20	CLOSED	2026-10-31 00:25:27.351547+05:30	2026-10-31 00:27:28.486068+05:30
5	2	Aryann<'s adda	\N	VOICE	f	20	CLOSED	2026-10-31 00:53:38.98182+05:30	2026-10-31 01:15:27.1995+05:30
6	2	Aryann<'s adda	\N	VOICE	f	20	CLOSED	2026-10-31 13:35:09.224563+05:30	2026-10-31 13:36:30.224266+05:30
7	2	Aryann<'s adda	\N	VOICE	f	20	CLOSED	2026-10-31 13:40:36.12833+05:30	2026-10-31 14:01:13.522593+05:30
8	2	Aryann<'s adda	\N	VOICE	f	20	CLOSED	2026-10-31 14:26:05.256532+05:30	2026-10-31 15:08:59.921927+05:30
9	1	Bharat's adda	asdfghjk	VOICE	f	20	ACTIVE	2026-10-31 17:35:34.083952+05:30	2026-10-31 17:35:34.083952+05:30
10	1	Bharat's adda 2	\N	VOICE	f	20	CLOSED	2026-10-31 19:12:42.321249+05:30	2026-10-31 19:13:02.222397+05:30
11	2	Aryann<'s adda	\N	VOICE	f	20	CLOSED	2026-11-01 14:08:28.118469+05:30	2026-11-01 14:13:41.384658+05:30
\.


--
-- Name: admin_accounts_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.admin_accounts_id_seq', 3, true);


--
-- Name: chat_conversation_participants_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.chat_conversation_participants_id_seq', 6, true);


--
-- Name: chat_conversations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.chat_conversations_id_seq', 3, true);


--
-- Name: chat_inspection_audit_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.chat_inspection_audit_id_seq', 1, false);


--
-- Name: chat_message_receipts_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.chat_message_receipts_id_seq', 14, true);


--
-- Name: chat_messages_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.chat_messages_id_seq', 18, true);


--
-- Name: coin_packs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.coin_packs_id_seq', 7, true);


--
-- Name: coin_transactions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.coin_transactions_id_seq', 2, true);


--
-- Name: coin_wallets_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.coin_wallets_id_seq', 2, true);


--
-- Name: email_otps_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.email_otps_id_seq', 7, true);


--
-- Name: payment_transactions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.payment_transactions_id_seq', 1, false);


--
-- Name: red_packet_products_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.red_packet_products_id_seq', 3, true);


--
-- Name: shop_categories_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.shop_categories_id_seq', 3, true);


--
-- Name: shop_products_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.shop_products_id_seq', 8, true);


--
-- Name: user_equipped_cosmetics_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.user_equipped_cosmetics_id_seq', 14, true);


--
-- Name: user_red_packet_inventory_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.user_red_packet_inventory_id_seq', 1, false);


--
-- Name: user_unlocked_cosmetics_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.user_unlocked_cosmetics_id_seq', 8, true);


--
-- Name: users_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.users_id_seq', 4, true);


--
-- Name: vip_cosmetics_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.vip_cosmetics_id_seq', 27, true);


--
-- Name: voice_room_chat_messages_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.voice_room_chat_messages_id_seq', 9, true);


--
-- Name: voice_room_chat_receipts_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.voice_room_chat_receipts_id_seq', 1, false);


--
-- Name: voice_room_participants_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.voice_room_participants_id_seq', 19, true);


--
-- Name: voice_rooms_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.voice_rooms_id_seq', 11, true);


--
-- Name: admin_accounts admin_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.admin_accounts
    ADD CONSTRAINT admin_accounts_pkey PRIMARY KEY (id);


--
-- Name: admin_accounts admin_accounts_username_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.admin_accounts
    ADD CONSTRAINT admin_accounts_username_key UNIQUE (username);


--
-- Name: chat_conversation_participants chat_conversation_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_conversation_participants
    ADD CONSTRAINT chat_conversation_participants_pkey PRIMARY KEY (id);


--
-- Name: chat_inspection_audit chat_inspection_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_inspection_audit
    ADD CONSTRAINT chat_inspection_audit_pkey PRIMARY KEY (id);


--
-- Name: chat_message_receipts chat_message_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_message_receipts
    ADD CONSTRAINT chat_message_receipts_pkey PRIMARY KEY (id);


--
-- Name: chat_messages chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_pkey PRIMARY KEY (id);


--
-- Name: coin_packs coin_packs_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coin_packs
    ADD CONSTRAINT coin_packs_pkey PRIMARY KEY (id);


--
-- Name: coin_transactions coin_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coin_transactions
    ADD CONSTRAINT coin_transactions_pkey PRIMARY KEY (id);


--
-- Name: coin_wallets coin_wallets_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coin_wallets
    ADD CONSTRAINT coin_wallets_pkey PRIMARY KEY (id);


--
-- Name: coin_wallets coin_wallets_user_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coin_wallets
    ADD CONSTRAINT coin_wallets_user_id_key UNIQUE (user_id);


--
-- Name: email_otps email_otps_email_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.email_otps
    ADD CONSTRAINT email_otps_email_key UNIQUE (email);


--
-- Name: email_otps email_otps_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.email_otps
    ADD CONSTRAINT email_otps_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: payment_transactions payment_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payment_transactions
    ADD CONSTRAINT payment_transactions_pkey PRIMARY KEY (id);


--
-- Name: payment_transactions payment_transactions_txn_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payment_transactions
    ADD CONSTRAINT payment_transactions_txn_id_key UNIQUE (txn_id);


--
-- Name: red_packet_products red_packet_products_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.red_packet_products
    ADD CONSTRAINT red_packet_products_pkey PRIMARY KEY (id);


--
-- Name: shop_categories shop_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shop_categories
    ADD CONSTRAINT shop_categories_pkey PRIMARY KEY (id);


--
-- Name: shop_products shop_products_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shop_products
    ADD CONSTRAINT shop_products_pkey PRIMARY KEY (id);


--
-- Name: coin_wallets uk_coin_wallets_user_id; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coin_wallets
    ADD CONSTRAINT uk_coin_wallets_user_id UNIQUE (user_id);


--
-- Name: shop_categories uk_shop_categories_name; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shop_categories
    ADD CONSTRAINT uk_shop_categories_name UNIQUE (name);


--
-- Name: user_red_packet_inventory uk_user_red_packet; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_red_packet_inventory
    ADD CONSTRAINT uk_user_red_packet UNIQUE (user_id, red_packet_product_id);


--
-- Name: user_red_packet_inventory uk_user_red_packet_inventory; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_red_packet_inventory
    ADD CONSTRAINT uk_user_red_packet_inventory UNIQUE (user_id, red_packet_product_id);


--
-- Name: chat_conversation_participants uq_chat_conversation_user; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_conversation_participants
    ADD CONSTRAINT uq_chat_conversation_user UNIQUE (conversation_id, user_id);


--
-- Name: chat_conversations uq_chat_conversations_id; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_conversations
    ADD CONSTRAINT uq_chat_conversations_id PRIMARY KEY (id);


--
-- Name: chat_message_receipts uq_chat_message_receipt_user; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_message_receipts
    ADD CONSTRAINT uq_chat_message_receipt_user UNIQUE (message_id, user_id);


--
-- Name: user_equipped_cosmetics uq_user_equipped_cosmetic_type; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_equipped_cosmetics
    ADD CONSTRAINT uq_user_equipped_cosmetic_type UNIQUE (user_id, cosmetic_type);


--
-- Name: user_favorite_games uq_user_favorite_games_game; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_favorite_games
    ADD CONSTRAINT uq_user_favorite_games_game UNIQUE (user_id, game);


--
-- Name: user_favorite_games uq_user_favorite_games_order; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_favorite_games
    ADD CONSTRAINT uq_user_favorite_games_order UNIQUE (user_id, game_order);


--
-- Name: user_unlocked_cosmetics uq_user_unlocked_cosmetic; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_unlocked_cosmetics
    ADD CONSTRAINT uq_user_unlocked_cosmetic UNIQUE (user_id, cosmetic_id);


--
-- Name: vip_cosmetics uq_vip_cosmetic_level_type_format; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vip_cosmetics
    ADD CONSTRAINT uq_vip_cosmetic_level_type_format UNIQUE (vip_level, cosmetic_type, format);


--
-- Name: voice_room_chat_receipts uq_voice_room_chat_receipt_user; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_receipts
    ADD CONSTRAINT uq_voice_room_chat_receipt_user UNIQUE (message_id, user_id);


--
-- Name: user_equipped_cosmetics user_equipped_cosmetics_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_equipped_cosmetics
    ADD CONSTRAINT user_equipped_cosmetics_pkey PRIMARY KEY (id);


--
-- Name: user_red_packet_inventory user_red_packet_inventory_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_red_packet_inventory
    ADD CONSTRAINT user_red_packet_inventory_pkey PRIMARY KEY (id);


--
-- Name: user_unlocked_cosmetics user_unlocked_cosmetics_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_unlocked_cosmetics
    ADD CONSTRAINT user_unlocked_cosmetics_pkey PRIMARY KEY (id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: vip_cosmetics vip_cosmetics_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vip_cosmetics
    ADD CONSTRAINT vip_cosmetics_pkey PRIMARY KEY (id);


--
-- Name: vip_cosmetics vip_cosmetics_public_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vip_cosmetics
    ADD CONSTRAINT vip_cosmetics_public_id_key UNIQUE (public_id);


--
-- Name: voice_room_chat_messages voice_room_chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_messages
    ADD CONSTRAINT voice_room_chat_messages_pkey PRIMARY KEY (id);


--
-- Name: voice_room_chat_receipts voice_room_chat_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_receipts
    ADD CONSTRAINT voice_room_chat_receipts_pkey PRIMARY KEY (id);


--
-- Name: voice_room_participants voice_room_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_participants
    ADD CONSTRAINT voice_room_participants_pkey PRIMARY KEY (id);


--
-- Name: voice_rooms voice_rooms_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_rooms
    ADD CONSTRAINT voice_rooms_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_chat_conversation_participants_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_conversation_participants_user ON public.chat_conversation_participants USING btree (user_id, conversation_id);


--
-- Name: idx_chat_conversations_updated_at; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_conversations_updated_at ON public.chat_conversations USING btree (updated_at);


--
-- Name: idx_chat_inspection_audit_admin; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_inspection_audit_admin ON public.chat_inspection_audit USING btree (admin_account_id, created_at DESC);


--
-- Name: idx_chat_inspection_audit_conversation; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_inspection_audit_conversation ON public.chat_inspection_audit USING btree (conversation_id, created_at DESC);


--
-- Name: idx_chat_messages_conversation_created; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_messages_conversation_created ON public.chat_messages USING btree (conversation_id, created_at DESC, id DESC);


--
-- Name: idx_chat_messages_sender; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_messages_sender ON public.chat_messages USING btree (sender_user_id, created_at DESC);


--
-- Name: idx_chat_participant_conversation; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_participant_conversation ON public.chat_conversation_participants USING btree (conversation_id);


--
-- Name: idx_chat_participant_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_participant_user ON public.chat_conversation_participants USING btree (user_id);


--
-- Name: idx_chat_receipts_user_unread; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chat_receipts_user_unread ON public.chat_message_receipts USING btree (user_id, read_at);


--
-- Name: idx_coin_packs_active_sort; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_coin_packs_active_sort ON public.coin_packs USING btree (active, sort_order);


--
-- Name: idx_coin_transactions_reason; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_coin_transactions_reason ON public.coin_transactions USING btree (reason);


--
-- Name: idx_coin_transactions_user_created; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_coin_transactions_user_created ON public.coin_transactions USING btree (user_id, created_at DESC);


--
-- Name: idx_email_otps_expires_at; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_email_otps_expires_at ON public.email_otps USING btree (expires_at);


--
-- Name: idx_payment_transactions_purpose_product; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_transactions_purpose_product ON public.payment_transactions USING btree (purpose, product_id);


--
-- Name: idx_payment_transactions_status_created; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_transactions_status_created ON public.payment_transactions USING btree (status, created_at);


--
-- Name: idx_payment_transactions_user_created; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_transactions_user_created ON public.payment_transactions USING btree (user_id, created_at DESC);


--
-- Name: idx_red_packet_inventory_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_red_packet_inventory_user ON public.user_red_packet_inventory USING btree (user_id);


--
-- Name: idx_red_packet_products_active_sort; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_red_packet_products_active_sort ON public.red_packet_products USING btree (active, sort_order);


--
-- Name: idx_shop_categories_active_order; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_shop_categories_active_order ON public.shop_categories USING btree (active, display_order);


--
-- Name: idx_shop_products_active; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_shop_products_active ON public.shop_products USING btree (active);


--
-- Name: idx_shop_products_category_id; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_shop_products_category_id ON public.shop_products USING btree (category_id);


--
-- Name: idx_unlocked_cosmetics_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_unlocked_cosmetics_user ON public.user_unlocked_cosmetics USING btree (user_id);


--
-- Name: idx_user_equipped_cosmetics_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_user_equipped_cosmetics_user ON public.user_equipped_cosmetics USING btree (user_id);


--
-- Name: idx_vip_cosmetics_level; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_vip_cosmetics_level ON public.vip_cosmetics USING btree (vip_level);


--
-- Name: idx_voice_room_chat_messages_history; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_voice_room_chat_messages_history ON public.voice_room_chat_messages USING btree (room_id, created_at DESC, id DESC);


--
-- Name: idx_voice_room_chat_messages_sender; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_voice_room_chat_messages_sender ON public.voice_room_chat_messages USING btree (sender_user_id, created_at DESC);


--
-- Name: idx_voice_room_chat_receipts_user_unread; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_voice_room_chat_receipts_user_unread ON public.voice_room_chat_receipts USING btree (user_id, read_at);


--
-- Name: idx_voice_room_participants_room_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_voice_room_participants_room_status ON public.voice_room_participants USING btree (room_id, status);


--
-- Name: idx_voice_room_participants_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_voice_room_participants_user ON public.voice_room_participants USING btree (user_id);


--
-- Name: idx_voice_rooms_owner; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_voice_rooms_owner ON public.voice_rooms USING btree (owner_id);


--
-- Name: idx_voice_rooms_status_created; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_voice_rooms_status_created ON public.voice_rooms USING btree (status, created_at DESC);


--
-- Name: uk_coin_transactions_idempotency_key; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uk_coin_transactions_idempotency_key ON public.coin_transactions USING btree (idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: uq_voice_room_active_participant; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_voice_room_active_participant ON public.voice_room_participants USING btree (room_id, user_id) WHERE ((status)::text = 'JOINED'::text);


--
-- Name: chat_conversation_participants chat_conversation_participants_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_conversation_participants
    ADD CONSTRAINT chat_conversation_participants_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.chat_conversations(id) ON DELETE CASCADE;


--
-- Name: chat_conversation_participants chat_conversation_participants_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_conversation_participants
    ADD CONSTRAINT chat_conversation_participants_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: chat_inspection_audit chat_inspection_audit_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_inspection_audit
    ADD CONSTRAINT chat_inspection_audit_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.chat_conversations(id) ON DELETE RESTRICT;


--
-- Name: chat_message_receipts chat_message_receipts_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_message_receipts
    ADD CONSTRAINT chat_message_receipts_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.chat_messages(id) ON DELETE CASCADE;


--
-- Name: chat_message_receipts chat_message_receipts_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_message_receipts
    ADD CONSTRAINT chat_message_receipts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: chat_messages chat_messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.chat_conversations(id) ON DELETE CASCADE;


--
-- Name: chat_messages chat_messages_sender_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_sender_user_id_fkey FOREIGN KEY (sender_user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: user_unlocked_cosmetics fk91wtec3ftf4okayb5a6lagdy0; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_unlocked_cosmetics
    ADD CONSTRAINT fk91wtec3ftf4okayb5a6lagdy0 FOREIGN KEY (cosmetic_id) REFERENCES public.vip_cosmetics(id);


--
-- Name: coin_transactions fk_coin_transactions_user; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coin_transactions
    ADD CONSTRAINT fk_coin_transactions_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: coin_wallets fk_coin_wallets_user; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coin_wallets
    ADD CONSTRAINT fk_coin_wallets_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: user_red_packet_inventory fk_red_packet_inventory_product; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_red_packet_inventory
    ADD CONSTRAINT fk_red_packet_inventory_product FOREIGN KEY (red_packet_product_id) REFERENCES public.red_packet_products(id);


--
-- Name: user_red_packet_inventory fk_red_packet_inventory_user; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_red_packet_inventory
    ADD CONSTRAINT fk_red_packet_inventory_user FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: shop_products fk_shop_products_category; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shop_products
    ADD CONSTRAINT fk_shop_products_category FOREIGN KEY (category_id) REFERENCES public.shop_categories(id) ON DELETE RESTRICT;


--
-- Name: user_equipped_cosmetics fk_user_equipped_cosmetics_cosmetic; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_equipped_cosmetics
    ADD CONSTRAINT fk_user_equipped_cosmetics_cosmetic FOREIGN KEY (cosmetic_id) REFERENCES public.vip_cosmetics(id);


--
-- Name: user_equipped_cosmetics fk_user_equipped_cosmetics_user; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_equipped_cosmetics
    ADD CONSTRAINT fk_user_equipped_cosmetics_user FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: user_favorite_games fk_user_favorite_games_user; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_favorite_games
    ADD CONSTRAINT fk_user_favorite_games_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: user_unlocked_cosmetics fkl47fj2um3e10bxpb7rrywc808; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_unlocked_cosmetics
    ADD CONSTRAINT fkl47fj2um3e10bxpb7rrywc808 FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: payment_transactions payment_transactions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payment_transactions
    ADD CONSTRAINT payment_transactions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: voice_room_chat_messages voice_room_chat_messages_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_messages
    ADD CONSTRAINT voice_room_chat_messages_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.voice_rooms(id) ON DELETE CASCADE;


--
-- Name: voice_room_chat_messages voice_room_chat_messages_sender_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_messages
    ADD CONSTRAINT voice_room_chat_messages_sender_user_id_fkey FOREIGN KEY (sender_user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: voice_room_chat_receipts voice_room_chat_receipts_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_receipts
    ADD CONSTRAINT voice_room_chat_receipts_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.voice_room_chat_messages(id) ON DELETE CASCADE;


--
-- Name: voice_room_chat_receipts voice_room_chat_receipts_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_chat_receipts
    ADD CONSTRAINT voice_room_chat_receipts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: voice_room_participants voice_room_participants_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_participants
    ADD CONSTRAINT voice_room_participants_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.voice_rooms(id) ON DELETE CASCADE;


--
-- Name: voice_room_participants voice_room_participants_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_room_participants
    ADD CONSTRAINT voice_room_participants_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: voice_rooms voice_rooms_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voice_rooms
    ADD CONSTRAINT voice_rooms_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- PostgreSQL database dump complete
--

\unrestrict YWEavEe7GBpbhzU6wHypEIsDbUXi3SJZDQiHPPomgg4wqT1nSuvROEUIdY5NHkl

