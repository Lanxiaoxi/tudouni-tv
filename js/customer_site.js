// 数据源配置（苹果 CMS V10 格式）
// 以下源均已实测可用（接口返回标准 CMS JSON 且能搜到数据）。
// 注意：第三方采集站可能随时失效/换地址，失效时删除对应项即可；也可在网页“设置-自定义接口”里临时增删。
const CUSTOMER_SITES = {
    jinying: {
        api: 'http://jyzyapi.com/provide/vod',
        name: '金鹰资源',
    },
    guangsu: {
        api: 'http://api.guangsuapi.com/api.php/provide/vod',
        name: '光速资源',
    },
    uku: {
        api: 'http://api.ukuapi.com/api.php/provide/vod',
        name: 'U酷资源',
    },
    baidu: {
        api: 'https://api.apibdzy.com/api.php/provide/vod/',
        name: '百度资源',
    },
    wujin: {
        api: 'https://p2100.net/api.php/provide/vod/',
        name: '无尽资源',
    },
    subo: {
        api: 'https://api.wujinapi.com/api.php/provide/vod/',
        name: '速博资源',
    },
    modu: {
        api: 'https://caiji.moduapi.cc/api.php/provide/vod/',
        name: '魔都资源',
    },
    zuidazy: {
        api: 'http://zuidazy.me/api.php/provide/vod/',
        name: '最大资源',
    },
    huohu: {
        api: 'https://hhzyapi.com/api.php/provide/vod/',
        name: '火狐资源',
    },
    dadi: {
        api: 'https://dadiapi.com/feifei2',
        name: '大地资源',
    }
};

// 调用全局方法合并
if (window.extendAPISites) {
    window.extendAPISites(CUSTOMER_SITES);
} else {
    console.error("错误：请先加载 config.js！");
}
