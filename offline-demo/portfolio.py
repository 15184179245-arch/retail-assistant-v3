"""Offline portfolio entry point. All records in this file are synthetic."""
import argparse
import json
from inventory import offline as stock
from print.print_server import _price_tag_ops, _tspl_price_tag

def demonstration(query="演示耗材", wider=False):
    stock.init_db()
    db = stock.get_db()
    db.execute("INSERT INTO inventory_floors(id,name) VALUES (1,?)", ("演示楼层",))
    db.execute("INSERT INTO product_cache(id,name,sku,spec,sell_price,stock_qty,unit) VALUES (1,?,?,?,?,?,?)",
               ("演示耗材", "DEMO-101", "规格 A", 8.8, 12, "盒"))
    db.execute("INSERT INTO product_cache(id,name,sku,spec,sell_price,stock_qty,unit) VALUES (2,?,?,?,?,?,?)",
               ("演示螺母", "DEMO-102", "规格 B", 3.5, 6, "袋"))
    stock.create_zone(1, "演示区")
    stock.create_rack(1, "演示货架")
    stock.create_level(1, "演示层")
    stock.create_cell(1, "演示格", "DEMO-01")
    stock.create_cell(1, "第二演示格", "DEMO-02")
    stock.set_product_cell(2, "DEMO-02", qty=6)
    outcome = stock.set_product_cell(1, "DEMO-01", qty=12)
    assert outcome["success"]
    args = ("演示耗材", "8.80", "DEMO-101")
    width, height = (60, 40) if wider else (50, 30)
    label_ops = _price_tag_ops(*args, spec="规格 A", width_mm=width, height_mm=height, unit="盒")
    label_tspl = _tspl_price_tag(*args, spec="规格 A", width=width, height=height, unit="盒")
    return {
        "synthetic": True,
        "inventory_tree": stock.get_full_tree()["data"],
        "search": stock.search_products_for_location(query)["data"],
        "print_ops": label_ops,
        "print_tspl": label_tspl,
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tspl", action="store_true", help="render printer command text without sending it")
    args = parser.parse_args()
    result = demonstration()
    print(result["print_tspl"] if args.tspl else json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
