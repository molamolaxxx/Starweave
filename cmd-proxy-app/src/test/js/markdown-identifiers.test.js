const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/resources/configui/assets/js/resources.js'), 'utf8')
const context = vm.createContext({
    esc: value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
})
vm.runInContext(source.slice(source.indexOf('function inlineMarkdown('),
    source.indexOf('function renderStructuredMemory(')), context)

test('preserves table names in the reported multi-name paragraph', () => {
    const names = 'md_bom、md_bom_item、md_category、md_price、md_sku、md_sku_extension、' +
        'pp_purchase_plan、pp_purchase_plan_item、t_faud_cash_refund_detail、' +
        't_faud_employee_simple_info'
    assert.equal(context.renderMarkdown('**主数据/采购：** ' + names),
        '<p><strong>主数据/采购：</strong> ' + names + '</p>')
})

test('preserves single and double underscores within identifiers', () => {
    for (const name of ['a_b_c', 'foo__bar__baz', '_private_field', 'field_name_',
        '表_退款_明细', '表__退款__明细', 'v1_2_3', 'e\u0301_field_name']) {
        assert.equal(context.inlineMarkdown(name), name)
    }
})

test('continues rendering explicit emphasis and bold with punctuation boundaries', () => {
    assert.equal(context.inlineMarkdown('_斜体_、__加粗__ (_x_) __y__'),
        '<em>斜体</em>、<strong>加粗</strong> (<em>x</em>) <strong>y</strong>')
    assert.equal(context.inlineMarkdown('*斜体* **t_faud_cash_refund_detail**'),
        '<em>斜体</em> <strong>t_faud_cash_refund_detail</strong>')
    assert.equal(context.inlineMarkdown('_ padded _ __ padded __'),
        '_ padded _ __ padded __')
})

test('keeps code and escaped underscores literal', () => {
    assert.equal(context.inlineMarkdown('`t_faud_cash_refund_detail` \\_literal\\_'),
        '<code>t_faud_cash_refund_detail</code> _literal_')
})

test('preserves identifiers in markdown table cells and lists', () => {
    assert.match(context.renderMarkdown('| 表名 |\n| --- |\n| t_faud_cash_refund_detail |'),
        /<td>t_faud_cash_refund_detail<\/td>/)
    assert.equal(context.renderMarkdown('- t_faud_cash_refund_detail'),
        '<ul><li>t_faud_cash_refund_detail</li></ul>')
})
