// 单源搜索（重构后）：调后端 /api/search 聚合接口
// 返回与旧实现一致的结果数组（item 已含 source_name / source_code）
async function searchByAPIAndKeyWord(apiId, query) {
    try {
        const params = { wd: query, page: 1 };

        // 处理自定义API
        if (apiId.startsWith('custom_')) {
            const customIndex = apiId.replace('custom_', '');
            const customApi = getCustomApiInfo(customIndex);
            if (!customApi) return [];
            params.source = 'custom';
            params.api_url = customApi.url;
        } else {
            // 内置API
            if (!API_SITES[apiId]) return [];
            params.source = apiId;
        }

        const data = await window.Api.get('/api/search', params);
        return (data && data.items) || [];
    } catch (error) {
        console.warn(`API ${apiId} 搜索失败:`, error);
        return [];
    }
}
