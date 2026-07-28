'use strict';

/* ================================================================
 * 드래그 선택형 사과 보드 컴포넌트 (솔로/대전 공용)
 *  - setBoard(matrix)     : 2차원 숫자 배열로 보드 렌더링 (0 = 제거됨)
 *  - removeCells(cells)   : [{r, c}] 목록을 제거 애니메이션과 함께 지움
 *  - setActive(bool)      : 드래그 입력 활성/비활성
 *  - onSelect({r1,c1,r2,c2,sum,cells}) : 드래그를 놓는 순간 호출
 * ================================================================ */
function createBoard({ wrapEl, boardEl, selBoxEl, onSelect }) {
    let matrix = [];
    let rows = 0, cols = 0;
    let cellEls = [];
    let active = false;
    let drag = null; // { startR, startC, curR, curC }

    function setBoard(newMatrix) {
        matrix = newMatrix.map((row) => [...row]);
        rows = matrix.length;
        cols = rows ? matrix[0].length : 0;
        render();
    }

    function clear() {
        matrix = [];
        rows = cols = 0;
        cellEls = [];
        boardEl.innerHTML = '';
        selBoxEl.classList.add('hidden');
    }

    function setActive(v) {
        active = v;
        if (!v) cancelDrag();
    }

    function isEmpty() {
        return matrix.every((row) => row.every((v) => v === 0));
    }

    function render() {
        boardEl.style.gridTemplateColumns = `repeat(${cols}, 34px)`;
        boardEl.innerHTML = '';
        cellEls = [];
        for (let r = 0; r < rows; r++) {
            cellEls[r] = [];
            for (let c = 0; c < cols; c++) {
                const v = matrix[r][c];
                const cell = document.createElement('div');
                cell.className = 'cell' + (v === 0 ? ' dead' : '');
                cell.textContent = v === 0 ? '' : v;
                boardEl.appendChild(cell);
                cellEls[r][c] = cell;
            }
        }
    }

    function removeCells(cells) {
        for (const { r, c } of cells) {
            if (!matrix[r] || !matrix[r][c]) continue;
            matrix[r][c] = 0;
            const el = cellEls[r][c];
            el.classList.add('pop');
            setTimeout(() => {
                el.classList.remove('pop', 'picked');
                el.classList.add('dead');
                el.textContent = '';
            }, 280);
        }
    }

    /* ---------------- 좌표 계산 ---------------- */
    // 첫 셀의 위치와 셀 간 간격을 실제 DOM 에서 측정 — CSS 가 바뀌어도 따라간다.
    function metrics() {
        const origin = cellEls[0][0].getBoundingClientRect();
        const strideX = cols > 1
            ? cellEls[0][1].getBoundingClientRect().left - origin.left
            : origin.width;
        const strideY = rows > 1
            ? cellEls[1][0].getBoundingClientRect().top - origin.top
            : origin.height;
        return { left: origin.left, top: origin.top, strideX, strideY };
    }

    function insideBoard(x, y) {
        const rect = boardEl.getBoundingClientRect();
        return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    }

    // 포인터 좌표 → 격자 셀. gap 이나 제거된 칸 위에서도 행/열이 끊기지 않는다.
    function cellFromPoint(x, y) {
        if (!cellEls.length) return null;
        const m = metrics();
        let c = Math.floor((x - m.left) / m.strideX);
        let r = Math.floor((y - m.top) / m.strideY);
        r = Math.max(0, Math.min(rows - 1, r));
        c = Math.max(0, Math.min(cols - 1, c));
        return { r, c };
    }

    function range() {
        return {
            r1: Math.min(drag.startR, drag.curR), r2: Math.max(drag.startR, drag.curR),
            c1: Math.min(drag.startC, drag.curC), c2: Math.max(drag.startC, drag.curC),
        };
    }

    function selection() {
        const { r1, r2, c1, c2 } = range();
        let sum = 0;
        const cells = [];
        for (let r = r1; r <= r2; r++) {
            for (let c = c1; c <= c2; c++) {
                if (matrix[r][c] !== 0) {
                    sum += matrix[r][c];
                    cells.push({ r, c });
                }
            }
        }
        return { r1, c1, r2, c2, sum, cells };
    }

    /* ---------------- 드래그 입력 ---------------- */
    wrapEl.addEventListener('pointerdown', (e) => {
        if (!active || !rows) return;
        if (!insideBoard(e.clientX, e.clientY)) return;
        const cell = cellFromPoint(e.clientX, e.clientY);
        if (!cell) return;
        wrapEl.setPointerCapture(e.pointerId);
        drag = { startR: cell.r, startC: cell.c, curR: cell.r, curC: cell.c };
        updateSelection();
    });

    wrapEl.addEventListener('pointermove', (e) => {
        if (!drag) return;
        const cell = cellFromPoint(e.clientX, e.clientY);
        if (cell) { drag.curR = cell.r; drag.curC = cell.c; }
        updateSelection();
    });

    wrapEl.addEventListener('pointerup', () => {
        if (!drag) return;
        const sel = selection();
        cancelDrag();
        onSelect(sel);
    });

    function cancelDrag() {
        drag = null;
        selBoxEl.classList.add('hidden');
        boardEl.querySelectorAll('.cell.picked').forEach((el) => el.classList.remove('picked'));
    }

    function updateSelection() {
        const { r1, r2, c1, c2, sum } = selection();

        boardEl.querySelectorAll('.cell.picked').forEach((el) => el.classList.remove('picked'));
        for (let r = r1; r <= r2; r++) {
            for (let c = c1; c <= c2; c++) {
                if (matrix[r][c] !== 0) cellEls[r][c].classList.add('picked');
            }
        }

        const wrap = wrapEl.getBoundingClientRect();
        const a = cellEls[r1][c1].getBoundingClientRect();
        const b = cellEls[r2][c2].getBoundingClientRect();
        selBoxEl.classList.remove('hidden');
        selBoxEl.style.left = (a.left - wrap.left) + 'px';
        selBoxEl.style.top = (a.top - wrap.top) + 'px';
        selBoxEl.style.width = (b.right - a.left) + 'px';
        selBoxEl.style.height = (b.bottom - a.top) + 'px';
        selBoxEl.style.borderColor = sum === 10 ? '#22c55e' : '#64748b';
    }

    return { setBoard, clear, removeCells, setActive, isEmpty };
}
