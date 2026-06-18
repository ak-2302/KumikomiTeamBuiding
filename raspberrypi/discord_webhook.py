import requests

webhook_url = "https://discord.com/api/webhooks/1517079150962409562/VtjPwUyTrI3V2vP0i-j_X-gUiuAxVZ2Nw1J_HcrXUf33FhLJsIfe7hxX5-TMmaa0slKa"

def send_message(message="",has_embed=False, embed_title="", embed_description="", embed_color=0x000000):
    data = {
        "content": message
    }
    if has_embed:
        embed = {
            "title": embed_title,
            "description": embed_description,
            "color": embed_color
        }
        
        data["embeds"] = [embed]
        requests.post(webhook_url, json=data)
    return requests.post(webhook_url, json=data)

if __name__ == "__main__":
    send_message(
        message="Hello from Raspberry Pi!",
        has_embed=True,
        embed_title="Sample Embed",
        embed_description="This is an example of an embedded message.",
        embed_color=0xFF5733
    )