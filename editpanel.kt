
    private fun createEditPanelView(tile: TileEntity): View {
        val scrollView = ScrollView(context)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
        }

        val titleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLayout.addView(TextView(context).apply {
            text = "编辑磁贴"
            textSize = 18f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleLayout.addView(TextView(context).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(20, 0, 0, 0)
            setOnClickListener { hideEditPanel() }
        })
        rootLayout.addView(titleLayout)

        rootLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = 10; bottomMargin = 10
            }
            setBackgroundColor(Color.LTGRAY)
        })

        rootLayout.addView(TextView(context).apply {
            text = "标签"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 0, 0, 5)
        })
        val labelInput = EditText(context).apply {
            setText(tile.tileLabel); setSingleLine(); setPadding(15, 10, 15, 10)
            isFocusable = true; isFocusableInTouchMode = true
        }
        rootLayout.addView(labelInput)
        labelInput.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                labelInput.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(labelInput, InputMethodManager.SHOW_IMPLICIT)
            }
            false
        }

        rootLayout.addView(TextView(context).apply {
            text = "图标"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 15, 0, 5)
        })
        val iconLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val iconPreview = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(60.dpToPx(), 60.dpToPx())
            scaleType = ImageView.ScaleType.FIT_CENTER
            tile.tilePackage?.let { pkg ->
                coroutineScope.launch(Dispatchers.IO) {
                    val icon = iconLoader.getIconForPackage(context, pkg)
                    withContext(Dispatchers.Main) {
                        icon?.let { setImageBitmap(it) }
                            ?: setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                }
            } ?: setImageResource(android.R.drawable.sym_def_app_icon)
        }
        iconLayout.addView(iconPreview)
        iconLayout.addView(TextView(context).apply {
            text = "点击选择图标"; textSize = 14f; setTextColor(Color.BLUE); setPadding(15, 0, 0, 0)
            setOnClickListener {
                val intent = Intent(context, TileCustomizeActivity::class.java).apply {
                    putExtra(TileCustomizeActivity.EXTRA_TILE_ID, tile.id)
                    putExtra(TileCustomizeActivity.EXTRA_TILE_PACKAGE, tile.tilePackage)
                    putExtra(TileCustomizeActivity.EXTRA_TILE_LABEL, tile.tileLabel)
                    putExtra(TileCustomizeActivity.EXTRA_TILE_COLOR, tile.tileColor)
                    putExtra(TileCustomizeActivity.EXTRA_TILE_SIZE, tile.tileSize)
                    putExtra(TileCustomizeActivity.EXTRA_TILE_CORNER_RADIUS, tile.tileCornerRadius)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                hideEditPanel(); hidePanel()
            }
        })
        rootLayout.addView(iconLayout)

        rootLayout.addView(TextView(context).apply {
            text = "磁贴大小"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 15, 0, 5)
        })
        val sizeOptions = arrayOf("小", "中", "大")
        var selectedSize = tile.tileSize
        val sizeText = TextView(context).apply {
            text = "当前: ${sizeOptions[selectedSize]}"; setPadding(10, 5, 10, 5)
        }
        rootLayout.addView(sizeText)
        val sizeButton = android.widget.Button(context).apply {
            text = "选择大小"
            setOnClickListener {
                currentPopup?.dismiss()
                val popupView = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(8, 8, 8, 8)
                }
                sizeOptions.forEachIndexed { index, option ->
                    popupView.addView(TextView(context).apply {
                        text = option; textSize = 16f; setTextColor(Color.WHITE); setPadding(32, 16, 32, 16)
                        setOnClickListener {
                            selectedSize = index
                            sizeText.text = "当前: ${sizeOptions[selectedSize]}"
                            currentPopup?.dismiss()
                        }
                    })
                }
                currentPopup = PopupWindow(popupView, 150.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT, true)
                currentPopup?.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame))
                currentPopup?.showAsDropDown(it, 0, -it.height)
            }
        }
        rootLayout.addView(sizeButton)

        rootLayout.addView(TextView(context).apply {
            text = "颜色"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 15, 0, 5)
        })
        var selectedColor = tile.tileColor ?: "#FF0050EF"
        val colorPreview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40.dpToPx()).apply {
                setMargins(0, 5, 0, 5)
            }
            setBackgroundColor(Color.parseColor(selectedColor))
        }
        rootLayout.addView(colorPreview)
        val colorButton = android.widget.Button(context).apply {
            text = "选择颜色"
            setOnClickListener {
                currentPopup?.dismiss()
                val popupView = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(8, 8, 8, 8)
                }
                standardColors.forEach { (color, name) ->
                    val itemLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 8, 16, 8)
                    }
                    itemLayout.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx())
                        setBackgroundColor(Color.parseColor(color))
                    })
                    itemLayout.addView(TextView(context).apply {
                        text = name; textSize = 14f; setTextColor(Color.WHITE); setPadding(16, 0, 0, 0)
                    })
                    itemLayout.setOnClickListener {
                        selectedColor = color
                        colorPreview.setBackgroundColor(Color.parseColor(selectedColor))
                        currentPopup?.dismiss()
                    }
                    popupView.addView(itemLayout)
                }
                currentPopup = PopupWindow(popupView, 250.dpToPx(), 450.dpToPx(), true)
                currentPopup?.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame))
                currentPopup?.showAsDropDown(it, 0, -it.height)
            }
        }
        rootLayout.addView(colorButton)

        rootLayout.addView(TextView(context).apply {
            text = "圆角大小"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 15, 0, 5)
        })
        val cornerSeekBar = SeekBar(context).apply {
            max = 20; progress = if (tile.tileCornerRadius != -1) tile.tileCornerRadius else 0
        }
        rootLayout.addView(cornerSeekBar)
        val cornerValue = TextView(context).apply {
            text = "${cornerSeekBar.progress} dp"; setPadding(0, 5, 0, 5)
        }
        rootLayout.addView(cornerValue)
        cornerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                cornerValue.text = "$progress dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rootLayout.addView(android.widget.Button(context).apply {
            text = "保存"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20
            }
            setOnClickListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(labelInput.windowToken, 0)
                if (labelInput.text.toString().isNotEmpty()) tile.tileLabel = labelInput.text.toString()
                tile.tileSize = selectedSize
                tile.tileColor = selectedColor
                tile.tileCornerRadius = cornerSeekBar.progress
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        db?.getTilesDao()?.updateTile(tile)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
                            refreshTiles(); hideEditPanel()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
        rootLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dpToPx())
        })
        scrollView.addView(rootLayout)
        return scrollView
    }
