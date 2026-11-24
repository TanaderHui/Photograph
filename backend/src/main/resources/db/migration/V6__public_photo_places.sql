CREATE TABLE photograph_app.public_photo_places (
    id text PRIMARY KEY,
    name text NOT NULL,
    city text NOT NULL,
    category text NOT NULL CHECK (category IN ('SCENIC', 'SPOT')),
    description text NOT NULL,
    location public.geometry(Point, 4326) NOT NULL,
    withdrawn_at timestamptz
);

CREATE INDEX public_photo_places_location ON photograph_app.public_photo_places USING gist (location)
    WHERE withdrawn_at IS NULL;

INSERT INTO photograph_app.public_photo_places (id, name, city, category, description, location)
VALUES
    ('beijing-summer-palace', '颐和园', '北京', 'SCENIC', '湖岸与园林建筑。', public.ST_SetSRID(public.ST_MakePoint(116.2755, 39.9999), 4326)),
    ('beijing-jingshan', '景山公园', '北京', 'SPOT', '可拍城市天际线与故宫建筑群。', public.ST_SetSRID(public.ST_MakePoint(116.3965, 39.9257), 4326)),
    ('shanghai-bund', '外滩', '上海', 'SPOT', '面向黄浦江和陆家嘴天际线。', public.ST_SetSRID(public.ST_MakePoint(121.4904, 31.2400), 4326)),
    ('shanghai-century-park', '世纪公园', '上海', 'SCENIC', '公园湖景和林地。', public.ST_SetSRID(public.ST_MakePoint(121.5520, 31.2150), 4326)),
    ('hangzhou-west-lake', '西湖', '杭州', 'SCENIC', '湖岸、山景和远处的城市轮廓。', public.ST_SetSRID(public.ST_MakePoint(120.1450, 30.2490), 4326)),
    ('hangzhou-broken-bridge', '断桥', '杭州', 'SPOT', '湖面、堤岸与桥景。', public.ST_SetSRID(public.ST_MakePoint(120.1510, 30.2607), 4326)),
    ('new-york-central-park', '中央公园', '纽约', 'SCENIC', '树林、步道与城市建筑。', public.ST_SetSRID(public.ST_MakePoint(-73.9654, 40.7829), 4326)),
    ('new-york-brooklyn-bridge-park', '布鲁克林大桥公园', '纽约', 'SPOT', '面向东河和曼哈顿天际线。', public.ST_SetSRID(public.ST_MakePoint(-73.9968, 40.7009), 4326));
